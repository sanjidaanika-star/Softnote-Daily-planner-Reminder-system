package backend.api;

import backend.db.ChapterDAO;
import backend.db.DBConnection;
import backend.db.DataAccessException;
import backend.db.ExamDAO;
import backend.db.SessionDAO;
import backend.db.UserDAO;
import backend.model.Chapter;
import backend.model.Exam;
import backend.model.StudySession;
import backend.model.User;
import backend.scheduler.DifficultyPlanStrategy;
import backend.scheduler.PlanService;
import backend.service.AuthService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public final class ApiServer {
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int MAX_LOGIN_SESSIONS = 4096;
    private static final long LOGIN_SESSION_NANOS = TimeUnit.HOURS.toNanos(8);

    private final HttpServer server;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final AuthService auth;
    private final ExamDAO exams;
    private final PlanService plans;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, LoginSession> loginSessions = new LinkedHashMap<>();

    public ApiServer(DBConnection database, int port, Clock clock) throws IOException {
        this.clock = clock;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(requests);
        auth = new AuthService(new UserDAO(database));
        exams = new ExamDAO(database);
        plans = new PlanService(exams, new ChapterDAO(database), new SessionDAO(database),
                new DifficultyPlanStrategy(), clock);
        route("/register", "POST", this::register);
        route("/login", "POST", this::login);
        route("/exams", "POST", this::createExam);
        route("/plan", "GET", this::plan);
        server.createContext("/", exchange -> {
            try (exchange) {
                error(exchange, 404, "Endpoint not found");
            }
        });
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 8080 : Integer.parseInt(args[0]);
        DBConnection database = args.length < 2 ? new DBConnection() : new DBConnection(Path.of(args[1]));
        ApiServer api = new ApiServer(database, port, Clock.systemDefaultZone());
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
        api.start();
        System.out.println("Softnote API listening on http://127.0.0.1:" + api.server.getAddress().getPort());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(2);
        requests.shutdownNow();
        plans.shutdown();
    }

    private void route(String path, String method, Endpoint endpoint) {
        server.createContext(path, exchange -> {
            try (exchange) {
                try {
                    if (!path.equals(exchange.getRequestURI().getPath())) {
                        throw new HttpError(404, "Endpoint not found");
                    }
                    if (!method.equals(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().set("Allow", method);
                        throw new HttpError(405, "Method not allowed");
                    }
                    endpoint.handle(exchange);
                } catch (HttpError exception) {
                    if (exception.status == 401) {
                        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    }
                    error(exchange, exception.status, exception.getMessage());
                } catch (IllegalArgumentException exception) {
                    error(exchange, 400, exception.getMessage());
                } catch (DataAccessException exception) {
                    System.err.println(exception);
                    error(exchange, 500, "Database operation failed");
                } catch (RuntimeException exception) {
                    System.err.println(exception);
                    error(exchange, 500, "Internal server error");
                }
            }
        });
    }

    private void register(HttpExchange exchange) throws IOException {
        Map<?, ?> body = requestBody(exchange);
        long id;
        try {
            id = auth.register(text(body, "name"), text(body, "email"), text(body, "password"));
        } catch (DataAccessException exception) {
            if (exception.getCause() instanceof SQLException sql && sql.getErrorCode() == 1062) {
                throw new HttpError(409, "An account with that email already exists");
            }
            throw exception;
        }
        reply(exchange, 201, "{\"id\":" + id + "}");
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<?, ?> body = requestBody(exchange);
        String email = text(body, "email");
        String password = text(body, "password");
        User user;
        try {
            user = auth.login(email, password);
        } catch (IllegalArgumentException exception) {
            throw new HttpError(401, "Invalid email or password.");
        }
        reply(exchange, 200, "{\"id\":" + user.getId() + ",\"name\":" + Json.quote(user.getName())
                + ",\"email\":" + Json.quote(user.getEmail())
                + ",\"token\":" + Json.quote(issueToken(user.getId())) + "}");
    }

    private synchronized String issueToken(long userId) {
        long now = System.nanoTime();
        loginSessions.values().removeIf(session -> now - session.startedAt >= LOGIN_SESSION_NANOS);
        if (loginSessions.size() >= MAX_LOGIN_SESSIONS) {
            loginSessions.remove(loginSessions.keySet().iterator().next());
        }
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        loginSessions.put(token, new LoginSession(userId, now));
        return token;
    }

    private synchronized long requireUser(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7);
            LoginSession session = loginSessions.get(token);
            if (session != null && System.nanoTime() - session.startedAt < LOGIN_SESSION_NANOS) {
                return session.userId;
            }
            loginSessions.remove(token);
        }
        throw new HttpError(401, "A valid login token is required");
    }

    private void createExam(HttpExchange exchange) throws IOException {
        long userId = requireUser(exchange);
        Map<?, ?> body = requestBody(exchange);
        LocalDate examDate;
        try {
            examDate = LocalDate.parse(text(body, "examDate"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("examDate must be a valid ISO date");
        }
        if (!examDate.isAfter(LocalDate.now(clock)) || examDate.getYear() > 9999) {
            throw new IllegalArgumentException("examDate must be after today and within MySQL's date range");
        }
        Exam exam = new Exam(0, userId, boundedText(body, "title"), examDate, positiveInt(body, "dailyMinutes"));
        List<ExamDAO.NewChapter> chapters = new ArrayList<>();
        if (!(body.get("chapters") instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("chapters must be a nonempty array");
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> chapter)) {
                throw new IllegalArgumentException("Each chapter must be an object");
            }
            String difficultyName = text(chapter, "difficulty");
            Chapter.Difficulty difficulty;
            try {
                difficulty = Chapter.Difficulty.valueOf(difficultyName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("difficulty must be easy, medium, or hard");
            }
            chapters.add(new ExamDAO.NewChapter(boundedText(chapter, "name"), difficulty,
                    positiveInt(chapter, "estimatedMinutes")));
        }
        long examId = exams.insertWithChapters(exam, chapters);
        reply(exchange, 201, "{\"id\":" + examId + "}");
    }

    private void plan(HttpExchange exchange) throws IOException {
        long userId = requireUser(exchange);
        String query = exchange.getRequestURI().getRawQuery();
        long examId;
        try {
            if (query == null || !query.matches("examId=[0-9]+")) {
                throw new NumberFormatException();
            }
            examId = Long.parseLong(query.substring(7));
            if (examId <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("examId must be a positive integer");
        }
        if (exams.findById(examId).filter(exam -> exam.getUserId() == userId).isEmpty()) {
            throw new HttpError(404, "Exam not found");
        }
        Future<List<StudySession>> generation = plans.generatePlan(examId);
        try {
            List<StudySession> sessions = generation.get(10, TimeUnit.SECONDS);
            String json = sessions.stream().map(ApiServer::sessionJson).collect(Collectors.joining(",", "[", "]"));
            reply(exchange, 200, json);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof java.util.NoSuchElementException) {
                throw new HttpError(404, "Exam not found");
            }
            System.err.println(exception.getCause());
            throw new HttpError(500, "Could not generate study plan");
        } catch (TimeoutException exception) {
            throw new HttpError(503, "Plan generation is still running. Try again shortly.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new HttpError(503, "Plan generation was interrupted");
        }
    }

    private static String sessionJson(StudySession session) {
        return "{\"id\":" + session.getId() + ",\"examId\":" + session.getExamId()
                + ",\"chapterId\":" + session.getChapterId()
                + ",\"sessionDate\":" + Json.quote(session.getSessionDate().toString())
                + ",\"plannedMinutes\":" + session.getPlannedMinutes()
                + ",\"status\":" + Json.quote(session.getStatus().name().toLowerCase(Locale.ROOT))
                + ",\"isRevision\":" + session.isRevision() + "}";
    }

    private static Map<?, ?> requestBody(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
            throw new HttpError(415, "Content-Type must be application/json");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new HttpError(413, "Request body is too large");
        }
        try {
            return Json.object(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString());
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Request body must be valid UTF-8");
        }
    }

    private static String text(Map<?, ?> object, String key) {
        if (!(object.get(key) instanceof String string)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return string;
    }

    private static String boundedText(Map<?, ?> object, String key) {
        String value = text(object, key);
        if (value.codePointCount(0, value.length()) > 255) {
            throw new IllegalArgumentException(key + " must be at most 255 characters");
        }
        return value;
    }

    private static int positiveInt(Map<?, ?> object, String key) {
        if (!(object.get(key) instanceof Long value) || value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return value.intValue();
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        reply(exchange, status, "{\"error\":" + Json.quote(message) + "}");
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Endpoint {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record LoginSession(long userId, long startedAt) {}

    private static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        private HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
