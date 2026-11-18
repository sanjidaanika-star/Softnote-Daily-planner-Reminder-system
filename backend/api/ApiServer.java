package backend.api;

import backend.ai.GeminiClient;
import backend.ai.QuestionGenerator;
import backend.ai.QuizService;
import backend.db.QuizDAO;
import backend.db.ChapterDAO;
import backend.db.DBConnection;
import backend.db.DataAccessException;
import backend.db.ExamDAO;
import backend.db.SessionDAO;
import backend.db.UserDAO;
import backend.db.AdminDAO;
import backend.db.ReminderDAO;
import backend.reminder.ReminderConfig;
import backend.reminder.ReminderService;
import backend.reminder.GmailSender;
import backend.reminder.WebPushSender;
import backend.reminder.BrevoSender;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
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
    private static final Map<String, String> ASSETS = Map.ofEntries(
            Map.entry("/assets/bg-study.svg", "image/svg+xml"),
            Map.entry("/assets/hero-study.png", "image/png"),
            Map.entry("/assets/hero-study.jpg", "image/jpeg"),
            Map.entry("/js/home.js", "text/javascript"),
            Map.entry("/index.html", "text/html"),
            Map.entry("/admin.html", "text/html"), Map.entry("/js/admin.js", "text/javascript"),
            Map.entry("/reset-password.html", "text/html"), Map.entry("/js/reset.js", "text/javascript"),
            Map.entry("/report.html", "text/html"), Map.entry("/js/report.js", "text/javascript"),
            Map.entry("/quiz.html", "text/html"), Map.entry("/js/quiz.js", "text/javascript"),
            Map.entry("/js/courses.js", "text/javascript"),
            Map.entry("/js/countdown.js", "text/javascript"),
            Map.entry("/login.html", "text/html"), Map.entry("/register.html", "text/html"),
            Map.entry("/dashboard.html", "text/html"), Map.entry("/new-exam.html", "text/html"),
            Map.entry("/exams.html", "text/html"), Map.entry("/study.html", "text/html"),
            Map.entry("/chapters.html", "text/html"),
            Map.entry("/reminders.html", "text/html"), Map.entry("/js/reminders.js", "text/javascript"),
            Map.entry("/sw.js", "text/javascript"),
            Map.entry("/plan.html", "text/html"), Map.entry("/progress.html", "text/html"), Map.entry("/css/style.css", "text/css"),
            Map.entry("/js/api.js", "text/javascript"), Map.entry("/js/pages.js", "text/javascript"));
    private static final int MAX_BODY_BYTES = 65_536;
    private static final int MAX_LOGIN_SESSIONS = 4096;
    private static final long LOGIN_SESSION_NANOS = TimeUnit.HOURS.toNanos(8);

    private final HttpServer server;
    private final ExecutorService requests = Executors.newFixedThreadPool(4);
    private final AuthService auth;
    private final UserDAO users;
    private final ExamDAO exams;
    private final ChapterDAO chapters;
    private final PlanService plans;
    private final SessionDAO sessions;
    private final Clock clock;
    private final ReminderService reminders;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, LoginSession> loginSessions = new LinkedHashMap<>();

    public ApiServer(DBConnection database, int port, Clock clock) throws IOException {
        this(database, port, clock, new GeminiClient(Path.of("config", "gemini.properties")));
    }

    public ApiServer(DBConnection database, int port, Clock clock, QuestionGenerator generator) throws IOException {
        this(database, new InetSocketAddress("127.0.0.1", port), clock, generator);
    }

    private ApiServer(DBConnection database, InetSocketAddress address, Clock clock, QuestionGenerator generator)
            throws IOException {
        this.clock = clock;
        server = HttpServer.create(address, 0);
        server.setExecutor(requests);
        users = new UserDAO(database);
        auth = new AuthService(users);
        ReminderConfig reminderConfig = ReminderConfig.load();
        ReminderDAO reminderDao = new ReminderDAO(database);
        reminders = new ReminderService(reminderDao,
                "smtp".equals(reminderConfig.value("mail.provider", "brevo")) ? new GmailSender(reminderConfig) : new BrevoSender(reminderConfig),
                new WebPushSender(reminderConfig), clock);
        ReminderRoutes reminderRoutes = new ReminderRoutes(reminderDao, reminderConfig, clock);
        route("/reminders", "GET", exchange -> reminderRoutes.handle(exchange, requireUser(exchange)));
        route("/reminders/", "GET, POST, PUT, PATCH, DELETE", exchange -> reminderRoutes.handle(exchange, requireUser(exchange)));
        AdminRoutes administration = new AdminRoutes(new AdminDAO(database));
        route("/admin/", "GET, POST, PATCH", exchange -> administration.handle(exchange, requireAdmin(exchange)));
        route("/password-reset/request", "POST", administration::requestReset);
        route("/password-reset/confirm", "POST", administration::resetPassword);
        route("/me", "GET", exchange -> {
            long id = requireUser(exchange);
            reply(exchange, 200, "{\"id\":" + id + ",\"role\":" + Json.quote(users.access(id).role()) + "}");
        });
        exams = new ExamDAO(database);
        chapters = new ChapterDAO(database);
        sessions = new SessionDAO(database);
        plans = new PlanService(sessions,
                new DifficultyPlanStrategy(), clock);
        QuizDAO quizDao = new QuizDAO(database);
        QuizRoutes quizzes = new QuizRoutes(quizDao, new QuizService(quizDao, generator, plans::executeBackground));
        route("/notes", "GET, POST", exchange -> quizzes.notes(exchange, requireUser(exchange)));
        route("/notes/", "POST", exchange -> quizzes.start(exchange, requireUser(exchange)));
        route("/quizzes/", "GET, POST", exchange -> quizzes.quiz(exchange, requireUser(exchange)));
        route("/register", "POST", this::register);
        route("/login", "POST", this::login);
        route("/exams", "GET, POST", this::handleExams);
        route("/exams/", "GET, PUT, DELETE", this::handleExam);
        route("/plan", "GET", this::plan);
        route("/progress", "GET", this::progress);
        route("/history", "GET", this::history);
        route("/sessions/", "PATCH", this::changeStatus);
        ASSETS.keySet().forEach(path -> server.createContext(path, this::serveFrontend));
        server.createContext("/", this::serveFrontend);
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args.length == 0 ? System.getenv().getOrDefault("PORT", "8080") : args[0]);
        String host = System.getenv().getOrDefault("SOFTNOTE_BIND_HOST", "127.0.0.1");
        DBConnection database = args.length < 2 ? new DBConnection() : new DBConnection(Path.of(args[1]));
        ApiServer api = new ApiServer(database, new InetSocketAddress(host, port), Clock.systemDefaultZone(),
                new GeminiClient(Path.of("config", "gemini.properties")));
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
        api.start();
        System.out.println("Softnote API listening on " + api.server.getAddress());
    }

    public void start() {
        server.start();
        reminders.start();
    }

    public void stop() {
        server.stop(2);
        reminders.close();
        requests.shutdownNow();
        plans.shutdown();
    }

    private void route(String path, String method, Endpoint endpoint) {
        server.createContext(path, exchange -> {
            try (exchange) {
                try {
                    String requestPath = exchange.getRequestURI().getPath();
                    if (!(path.endsWith("/") ? requestPath.startsWith(path) : path.equals(requestPath))) {
                        throw new HttpError(404, "Endpoint not found");
                    }
                    if (!List.of(method.split(", ")).contains(exchange.getRequestMethod())) {
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

    private void serveFrontend(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                path = "/index.html";
            }
            String contentType = ASSETS.getOrDefault(path == null ? "" : path, "");
            if (contentType.isEmpty()) {
                error(exchange, 404, "Page not found");
                return;
            }
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            if (!head && !"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                error(exchange, 405, "Method not allowed");
                return;
            }
            Path file;
            try {
                Path root = Path.of("frontend").toRealPath();
                file = root.resolve(path.substring(1)).toRealPath();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    error(exchange, 404, "Page not found");
                    return;
                }
            } catch (IOException exception) {
                error(exchange, 404, "Page not found");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(body.length));
            exchange.sendResponseHeaders(200, head || body.length == 0 ? -1 : body.length);
            if (!head && body.length != 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private void handleExams(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            createExam(exchange);
            return;
        }
        String json = exams.findByUserId(requireUser(exchange)).stream()
                .map(exam -> "{\"id\":" + exam.getId() + ",\"title\":" + Json.quote(exam.getTitle())
                        + ",\"examDate\":" + Json.quote(exam.getExamDate().toString())
                        + ",\"dailyMinutes\":" + exam.getDailyMinutes() + examMetadata(exam) + "}")
                .collect(Collectors.joining(",", "[", "]"));
        reply(exchange, 200, json);
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
                + ",\"role\":" + Json.quote(users.access(user.getId()).role())
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
        loginSessions.put(token, new LoginSession(userId, now, users.access(userId).version()));
        return token;
    }

    private synchronized long requireUser(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7);
            LoginSession session = loginSessions.get(token);
            if (session != null && System.nanoTime() - session.startedAt < LOGIN_SESSION_NANOS) {
                UserDAO.Access access = users.access(session.userId);
                if (access.active() && access.version() == session.version) return session.userId;
            }
            loginSessions.remove(token);
        }
        throw new HttpError(401, "A valid login token is required");
    }

    private long requireAdmin(HttpExchange exchange) {
        long id = requireUser(exchange);
        if (!"admin".equals(users.access(id).role())) throw new HttpError(403, "Administrator access is required");
        return id;
    }

    private static String examMetadata(Exam exam) {
        return ",\"semester\":" + exam.getSemester() + ",\"courseName\":" + Json.quote(exam.getCourseName())
                + ",\"assessment\":" + Json.quote(exam.getAssessment());
    }

    private void createExam(HttpExchange exchange) throws IOException {
        long userId = requireUser(exchange);
        Map<?, ?> body = requestBody(exchange);
        Exam exam = readExamInput(body, 0, userId);
        if (!exam.getExamDate().isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("examDate must be after today");
        }
        long examId = exams.insertWithChapters(exam, readChapterInputs(body).stream()
                .map(ExamDAO.ChapterEdit::details).toList());
        reply(exchange, 201, "{\"id\":" + examId + "}");
    }

    private void handleExam(HttpExchange exchange) throws IOException {
        long userId = requireUser(exchange);
        String path = exchange.getRequestURI().getPath();
        if (!path.matches("/exams/[1-9][0-9]*")) {
            throw new HttpError(404, "Exam not found");
        }
        long examId = Long.parseLong(path.substring("/exams/".length()));
        try {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exams.delete(examId, userId);
                reply(exchange, 200, "{\"message\":\"Exam and study plan deleted\"}");
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                Map<?, ?> body = requestBody(exchange);
                exams.update(readExamInput(body, examId, userId), readChapterInputs(body), LocalDate.now(clock));
                reply(exchange, 200, "{\"id\":" + examId + "}");
                return;
            }
            ExamDAO.ExamDetails details = exams.details(examId, userId);
            Exam exam = details.exam();
            String chaptersJson = details.chapters().stream()
                    .map(chapter -> "{\"id\":" + chapter.getId() + ",\"name\":" + Json.quote(chapter.getName())
                            + ",\"difficulty\":" + Json.quote(chapter.getDifficulty().name().toLowerCase(Locale.ROOT))
                            + ",\"estimatedMinutes\":" + chapter.getEstimatedMinutes() + "}")
                    .collect(Collectors.joining(",", "[", "]"));
            reply(exchange, 200, "{\"id\":" + examId + ",\"title\":" + Json.quote(exam.getTitle())
                    + ",\"examDate\":" + Json.quote(exam.getExamDate().toString())
                    + ",\"dailyMinutes\":" + exam.getDailyMinutes() + examMetadata(exam) + ",\"chapters\":" + chaptersJson + "}");
        } catch (java.util.NoSuchElementException exception) {
            throw new HttpError(404, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new HttpError(409, exception.getMessage());
        }
    }

    private static Exam readExamInput(Map<?, ?> body, long id, long userId) {
        LocalDate examDate;
        try {
            examDate = LocalDate.parse(text(body, "examDate"));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("examDate must be a valid ISO date");
        }
        if (examDate.getYear() < 1000 || examDate.getYear() > 9999) {
            throw new IllegalArgumentException("examDate must be within MySQL's date range");
        }
        Exam exam = new Exam(id, userId, boundedText(body, "title"), examDate, positiveInt(body, "dailyMinutes"));
        if (body.containsKey("semester")) {
            if (!(body.get("semester") instanceof Long value) || value < 0 || value > 12) {
                throw new IllegalArgumentException("Semester must be 0 to 12");
            }
            exam.setSemester(((Long) body.get("semester")).intValue());
        }
        if (body.containsKey("courseName")) exam.setCourseName(boundedText(body, "courseName"));
        if (body.containsKey("assessment")) exam.setAssessment(text(body, "assessment"));
        return exam;
    }

    private static List<ExamDAO.ChapterEdit> readChapterInputs(Map<?, ?> body) {
        List<ExamDAO.ChapterEdit> chapters = new ArrayList<>();
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
            Object chapterId = chapter.get("id");
            if (chapterId != null && (!(chapterId instanceof Long id) || id <= 0)) {
                throw new IllegalArgumentException("Chapter ID must be positive");
            }
            chapters.add(new ExamDAO.ChapterEdit(chapterId == null ? 0 : (Long) chapterId,
                    new ExamDAO.NewChapter(boundedText(chapter, "name"), difficulty,
                            positiveInt(chapter, "estimatedMinutes"))));
        }
        return chapters;
    }

    private long ownedExamId(HttpExchange exchange) {
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
        return examId;
    }

    private void plan(HttpExchange exchange) throws IOException {
        long examId = ownedExamId(exchange);
        await(plans.generatePlan(examId));
        SessionDAO.Plan plan = sessions.readPlan(examId);
        Map<Long, String> names = new HashMap<>();
        chapters.findByExamId(examId).forEach(chapter -> names.put(chapter.getId(), chapter.getName()));
        String items = plan.sessions().stream()
                .map(session -> sessionJson(session, names.getOrDefault(session.getChapterId(), "Unknown chapter")))
                .collect(Collectors.joining(",", "[", "]"));
        String unscheduled = plan.unscheduled().stream()
                .map(item -> "{\"chapterName\":" + Json.quote(item.chapterName()) + ",\"minutes\":" + item.minutes() + "}")
                .collect(Collectors.joining(",", "[", "]"));
        String chapterProgress = chapters.findByExamId(examId).stream()
                .map(chapter -> chapterProgressJson(chapter, plan.sessions()))
                .collect(Collectors.joining(",", "[", "]"));
        reply(exchange, 200, "{\"sessions\":" + items + ",\"unscheduled\":" + unscheduled
                + ",\"chapters\":" + chapterProgress + "}");
    }

    private static String chapterProgressJson(Chapter chapter, List<StudySession> sessions) {
        int weight = switch (chapter.getDifficulty()) {
            case EASY -> 1;
            case MEDIUM -> 2;
            case HARD -> 3;
        };
        long required = (long) chapter.getEstimatedMinutes() * weight;
        long completed = sessions.stream().filter(session -> session.getChapterId() == chapter.getId()
                        && !session.isRevision() && session.getStatus() == StudySession.Status.COMPLETED)
                .mapToLong(StudySession::getPlannedMinutes).sum();
        long revision = sessions.stream().filter(session -> session.getChapterId() == chapter.getId()
                        && session.isRevision() && session.getStatus() == StudySession.Status.COMPLETED)
                .mapToLong(StudySession::getPlannedMinutes).sum();
        return "{\"id\":" + chapter.getId() + ",\"name\":" + Json.quote(chapter.getName())
                + ",\"requiredMinutes\":" + required + ",\"completedMinutes\":" + completed
                + ",\"revisionCompletedMinutes\":" + revision + ",\"completed\":" + (completed >= required) + "}";
    }

    private void changeStatus(HttpExchange exchange) throws IOException {
        long userId = requireUser(exchange);
        String path = exchange.getRequestURI().getPath();
        if (!path.matches("/sessions/[0-9]+/status")) {
            throw new HttpError(404, "Endpoint not found");
        }
        long sessionId = Long.parseLong(path.substring(10, path.length() - 7));
        if (sessionId <= 0) {
            throw new IllegalArgumentException("Session ID must be positive");
        }
        Map<?, ?> body = requestBody(exchange);
        StudySession.Status status = body.containsKey("status") ? switch (text(body, "status")) {
            case "completed" -> StudySession.Status.COMPLETED;
            case "missed" -> StudySession.Status.MISSED;
            default -> throw new IllegalArgumentException("Status must be completed or missed");
        } : null;
        Object revision = body.get("isRevision");
        if (body.containsKey("isRevision") && !(revision instanceof Boolean)) {
            throw new IllegalArgumentException("isRevision must be true or false");
        }
        if (status == null && revision == null) {
            throw new IllegalArgumentException("Provide a status or revision value");
        }
        StudySession updated = await(plans.changeStatus(sessionId, userId, status, (Boolean) revision));
        String name = chapters.findByExamId(updated.getExamId()).stream()
                .filter(chapter -> chapter.getId() == updated.getChapterId()).map(Chapter::getName)
                .findFirst().orElse("Unknown chapter");
        reply(exchange, 200, sessionJson(updated, name));
    }

    private void progress(HttpExchange exchange) throws IOException {
        long examId = ownedExamId(exchange);
        await(plans.generatePlan(examId));
        SessionDAO.Plan plan = sessions.readPlan(examId);
        long total = plan.unscheduled().stream().mapToLong(SessionDAO.Unscheduled::minutes).sum();
        long completed = 0;
        for (StudySession session : plan.sessions()) {
            if (session.getStatus() != StudySession.Status.MISSED) {
                total += session.getPlannedMinutes();
            }
            if (session.getStatus() == StudySession.Status.COMPLETED) {
                completed += session.getPlannedMinutes();
            }
        }
        double percentage = total == 0 ? 0 : Math.round(10000.0 * completed / total) / 100.0;
        reply(exchange, 200, "{\"totalPlannedMinutes\":" + total + ",\"completedMinutes\":" + completed
                + ",\"percentage\":" + percentage + "}");
    }

    private void history(HttpExchange exchange) throws IOException {
        String json = sessions.findHistory(requireUser(exchange)).stream()
                .map(entry -> "{\"examTitle\":" + Json.quote(entry.examTitle()) + ","
                        + sessionJson(entry.session(), entry.chapterName()).substring(1))
                .collect(Collectors.joining(",", "[", "]"));
        reply(exchange, 200, json);
    }

    private static <T> T await(Future<T> task) {
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof java.util.NoSuchElementException) {
                throw new HttpError(404, cause.getMessage());
            }
            if (cause instanceof IllegalStateException) {
                throw new HttpError(409, cause.getMessage());
            }
            System.err.println(cause);
            throw new HttpError(500, "Could not save or load the study plan");
        } catch (TimeoutException exception) {
            throw new HttpError(503, "The request is still running. Try again shortly.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new HttpError(503, "The request was interrupted");
        }
    }

    private static String sessionJson(StudySession session, String chapterName) {
        return "{\"id\":" + session.getId() + ",\"examId\":" + session.getExamId()
                + ",\"chapterId\":" + session.getChapterId()
                + ",\"chapterName\":" + Json.quote(chapterName)
                + ",\"sessionDate\":" + Json.quote(session.getSessionDate().toString())
                + ",\"plannedMinutes\":" + session.getPlannedMinutes()
                + ",\"status\":" + Json.quote(session.getStatus().name().toLowerCase(Locale.ROOT))
                + ",\"isRevision\":" + session.isRevision() + "}";
    }

    static Map<?, ?> requestBody(HttpExchange exchange) throws IOException {
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

    static void reply(HttpExchange exchange, int status, String json) throws IOException {
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

    private record LoginSession(long userId, long startedAt, long version) {}

    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
