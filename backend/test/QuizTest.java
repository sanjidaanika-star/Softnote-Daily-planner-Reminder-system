package backend.test;

import backend.ai.GenerationException;
import backend.ai.QuizValidator;
import backend.api.ApiServer;
import backend.db.DBConnection;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class QuizTest {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "serve".equals(args[0])) {
            Files.write(Path.of(args[3]), pdf("Algorithms revision: binary search halves the search range in a sorted array."));
            ApiServer server = server(Path.of(args[1]), Integer.parseInt(args[2]));
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            server.start();
            System.out.println("Local fake-generator quiz test server ready");
            return;
        }
        var valid = new ArrayList<>(sampleQuestions());
        check(QuizValidator.parse(json(valid), 15).size() == 15, "Expected 15 valid questions");
        System.out.println("PASS parses 15 valid questions");
        valid.get(0).put("options", List.of("A", "B", "C"));
        check(QuizValidator.parse(json(valid), 15).size() == 14, "Three options should be rejected");
        System.out.println("PASS rejects a question with three options");
        valid.set(0, valid.get(1));
        check(QuizValidator.parse(json(valid), 15).size() == 14, "Duplicate should be removed");
        System.out.println("PASS removes exact duplicate questions");
        valid = new ArrayList<>(sampleQuestions());
        valid.get(0).put("correctIndex", 4);
        check(QuizValidator.parse(json(valid), 15).size() == 14, "Invalid answer index should be rejected");
        System.out.println("PASS rejects invalid correct index");
        if (args.length == 1) integration(Path.of(args[0]));
    }

    private static void integration(Path config) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        ApiServer server = server(config, port);
        server.start();
        try {
            String url = "http://127.0.0.1:" + port;
            String owner = account(url);
            String other = account(url);
            long note = upload(url, owner, "Algorithms revision: binary search halves the search range in a sorted array.");
            long failedNote = upload(url, owner, "FAIL FIXTURE");
            long failedId = invoke(url, "/notes/" + failedNote + "/quizzes", "POST", owner,
                    Map.of("count", 15), 202).get("id").getAsLong();
            JsonObject failed = waitForQuiz(url, owner, failedId);
            check("failed".equals(failed.get("status").getAsString()) && failed.has("failureReason"), "Missing failure state");
            System.out.println("PASS fake generator exception persists failed status");
            invoke(url, "/notes/" + note + "/quizzes", "POST", other, Map.of("count", 15), 404);
            long id = invoke(url, "/notes/" + note + "/quizzes", "POST", owner,
                    Map.of("count", 15), 202).get("id").getAsLong();
            JsonObject quiz = waitForQuiz(url, owner, id);
            JsonObject question = quiz.getAsJsonArray("questions").get(0).getAsJsonObject();
            check(!question.has("answer") && !question.has("correctIndex") && !question.has("explanation"),
                    "Unanswered question exposed its answer");
            String answerPath = "/quizzes/" + id + "/questions/" + question.get("id").getAsLong() + "/answer";
            invoke(url, "/quizzes/" + id, "GET", other, null, 404);
            invoke(url, "/quizzes/" + id + "/score", "GET", other, null, 404);
            invoke(url, answerPath, "POST", other, Map.of("chosenIndex", 0), 404);
            System.out.println("PASS foreign owner cannot generate, read, score, or answer");
            invoke(url, answerPath, "POST", owner, Map.of("chosenIndex", 0), 200);
            invoke(url, answerPath, "POST", owner, Map.of("chosenIndex", 1), 200);
            JsonObject score = invoke(url, "/quizzes/" + id + "/score", "GET", owner, null, 200);
            check(score.get("totalAnswered").getAsInt() == 1 && score.get("correct").getAsInt() == 1,
                    "An answer retry changed the original score");
            System.out.println("PASS real PDF upload, hidden answers, persisted score and idempotent retry");
        } finally {
            server.stop();
        }
    }

    private static ApiServer server(Path config, int port) throws Exception {
        DBConnection database = new DBConnection(config);
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement("SELECT DATABASE() = ?")) {
            statement.setString(1, "softnote_quiz_test");
            try (var rows = statement.executeQuery()) {
                check(rows.next() && rows.getBoolean(1), "Quiz tests require softnote_quiz_test");
            }
        }
        return new ApiServer(database, port, Clock.systemDefaultZone(), (text, count) -> {
            if (text.contains("FAIL FIXTURE")) throw new GenerationException("Fixture generator failed.");
            if (!text.contains("Algorithms revision")) throw new AssertionError("Expected selectable PDF text");
            return json(sampleQuestions());
        });
    }

    private static List<Map<String, Object>> sampleQuestions() {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int index = 1; index <= 15; index++) {
            questions.add(new java.util.HashMap<>(Map.of("question", "Algorithms fixture question " + index,
                    "options", List.of("Binary search", "Bubble sort", "Merge sort", "Linear search"),
                    "correctIndex", 0, "explanation", "Binary search halves the search range at each step.")));
        }
        return questions;
    }

    private static String json(List<Map<String, Object>> items) {
        return JSON.toJson(Map.of("questions", items));
    }

    private static JsonObject waitForQuiz(String url, String token, long id) throws Exception {
        for (int retry = 0; retry < 100; retry++) {
            JsonObject quiz = invoke(url, "/quizzes/" + id, "GET", token, null, 200);
            if (!"generating".equals(quiz.get("status").getAsString())) return quiz;
            Thread.sleep(30);
        }
        throw new AssertionError("Quiz was still generating");
    }

    private static String account(String url) throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        invoke(url, "/register", "POST", null, Map.of("name", "Quiz fixture", "email", email, "password", password), 201);
        return invoke(url, "/login", "POST", null, Map.of("email", email, "password", password), 200)
                .get("token").getAsString();
    }

    private static long upload(String url, String token, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/notes"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/pdf")
                .header("X-Note-Title", "Revision notes").POST(HttpRequest.BodyPublishers.ofByteArray(pdf(content))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == 201, "PDF upload failed: " + response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsLong();
    }

    private static byte[] pdf(String content) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.newLineAtOffset(40, 740);
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.showText(content);
                stream.endText();
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static JsonObject invoke(String url, String path, String method, String token,
                                     Map<String, Object> content, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (content != null) request.header("Content-Type", "application/json");
        request.method(method, content == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.toJson(content)));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == expected, path + " expected HTTP " + expected + ", got " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
