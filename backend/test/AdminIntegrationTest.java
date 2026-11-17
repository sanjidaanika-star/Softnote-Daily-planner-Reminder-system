package backend.test;

import backend.api.ApiServer;
import backend.db.DBConnection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.UUID;

public final class AdminIntegrationTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String ORIGIN = "http://127.0.0.1:18091";
    private static final String PASSWORD = "Test-password-123";
    private static DBConnection database;

    public static void main(String[] args) throws Exception {
        database = new DBConnection(Path.of(args[0]));
        try (var connection = database.getConnection();
             var statement = connection.prepareStatement("SELECT DATABASE() = ?")) {
            statement.setString(1, "softnote_test");
            try (var rows = statement.executeQuery()) {
                check(rows.next() && rows.getBoolean(1), "Use softnote_test");
            }
        }
        String prefix = UUID.randomUUID().toString();
        String adminEmail = prefix + "-admin@example.test", userEmail = prefix + "@example.test";
        long admin = 0, user = 0, exam = 0;
        ApiServer api = new ApiServer(database, 18091, Clock.systemUTC(), (text, count) -> "{}");
        api.start();
        try {
            admin = register(adminEmail);
            user = register(userEmail);
            execute("UPDATE users SET role = 'admin' WHERE id = ?", admin);
            String adminToken = login(adminEmail, PASSWORD), userToken = login(userEmail, PASSWORD);
            call("GET", "/admin/users", null, null, 401);
            call("GET", "/admin/users", userToken, null, 403);
            call("GET", "/admin/summary", adminToken, null, 200);
            String accounts = call("GET", "/admin/users", adminToken, null, 200).body();
            check(!accounts.contains("password_hash") && !accounts.contains("password_salt"), "No password data in admin responses");
            System.out.println("PASS admin-only monitoring and sensitive-field exclusion");
            String body = """
                    {"title":"Algorithms CT", "examDate":"2099-12-15", "dailyMinutes":30,
                    "semester":5,"courseName":"CSE 241 · Algorithms","assessment":"CT",
                    "chapters":[{"name":"Graphs","difficulty":"hard","estimatedMinutes":60}]}
                    """.replace("2099-12-15", java.time.LocalDate.now().plusDays(14).toString());
            exam = json(call("POST", "/exams", userToken, body, 201)).get("id").getAsLong();
            JsonObject saved = json(call("GET", "/exams/" + exam, userToken, null, 200));
            check(saved.get("semester").getAsInt() == 5 && saved.get("assessment").getAsString().equals("CT"), "Academic metadata saved");
            call("PUT", "/exams/" + exam, userToken, saved.toString().replace("\"CT\"", "\"MID\""), 200);
            check(json(call("GET", "/exams/" + exam, userToken, null, 200)).get("assessment").getAsString().equals("MID"), "Metadata updated");
            call("GET", "/exams/" + exam, adminToken, null, 404);
            JsonObject plan = json(call("GET", "/plan?examId=" + exam, userToken, null, 200));
            check(!plan.getAsJsonArray("chapters").get(0).getAsJsonObject().get("completed").getAsBoolean(), "New chapter is active");
            long firstSession = 0;
            for (var entry : plan.getAsJsonArray("sessions")) {
                JsonObject session = entry.getAsJsonObject();
                if (!session.get("isRevision").getAsBoolean()) {
                    firstSession = session.get("id").getAsLong();
                    call("PATCH", "/sessions/" + firstSession + "/status", userToken, "{\"status\":\"completed\"}", 200);
                    break;
                }
            }
            check(!json(call("GET", "/plan?examId=" + exam, userToken, null, 200)).getAsJsonArray("chapters").get(0).getAsJsonObject().get("completed").getAsBoolean(), "One session cannot complete a multi-session chapter");
            for (var entry : plan.getAsJsonArray("sessions")) {
                JsonObject session = entry.getAsJsonObject();
                if (!session.get("isRevision").getAsBoolean() && session.get("id").getAsLong() != firstSession) {
                    call("PATCH", "/sessions/" + session.get("id").getAsLong() + "/status", userToken, "{\"status\":\"completed\"}", 200);
                }
            }
            JsonObject completedChapter = json(call("GET", "/plan?examId=" + exam, userToken, null, 200)).getAsJsonArray("chapters").get(0).getAsJsonObject();
            check(completedChapter.get("completed").getAsBoolean() && completedChapter.get("requiredMinutes").getAsLong() == 180
                    && completedChapter.get("revisionCompletedMinutes").getAsLong() == 0, "Main study completion is independent of revision");
            System.out.println("PASS chapter automatically completes after all weighted study minutes; revision stays separate");
            System.out.println("PASS semester/course/assessment create and edit preserve ownership and plan generation");
            call("PATCH", "/admin/users/" + user + "/active", adminToken, "{\"active\":false}", 200);
            call("GET", "/me", userToken, null, 401);
            call("POST", "/login", null, credentials(userEmail, PASSWORD), 401);
            call("PATCH", "/admin/users/" + admin + "/active", adminToken, "{\"active\":false}", 400);
            call("PATCH", "/admin/users/" + user + "/active", adminToken, "{\"active\":true}", 200);
            userToken = login(userEmail, PASSWORD);
            System.out.println("PASS disabling invalidates sessions and blocks login; admin account protected");
            String resetBody = "{\"email\":\"" + userEmail + "\"}";
            String response = call("POST", "/password-reset/request", null, resetBody, 202).body();
            check(response.equals(call("POST", "/password-reset/request", null, "{\"email\":\"missing@example.test\"}", 202).body()), "Reset response must hide account existence");
            call("POST", "/password-reset/request", null, resetBody, 202);
            long reset;
            try (var connection = database.getConnection(); var statement = connection.prepareStatement("SELECT id FROM password_reset_requests WHERE user_id = ?")) {
                statement.setLong(1, user);
                try (var rows = statement.executeQuery()) { check(rows.next(), "Reset stored"); reset = rows.getLong(1); check(!rows.next(), "Requests deduplicated"); }
            }
            String path = json(call("POST", "/admin/resets/" + reset + "/issue", adminToken, "{}", 200)).get("resetPath").getAsString();
            String token = path.substring(path.indexOf('#') + 1);
            String confirm = "{\"token\":\"" + token + "\",\"password\":\"New-password-456\"}";
            execute("UPDATE password_reset_requests SET expires_at = CURRENT_TIMESTAMP - INTERVAL 1 SECOND WHERE id = ?", reset);
            call("POST", "/password-reset/confirm", null, confirm, 400);
            path = json(call("POST", "/admin/resets/" + reset + "/issue", adminToken, "{}", 200)).get("resetPath").getAsString();
            String newToken = path.substring(path.indexOf('#') + 1);
            call("POST", "/password-reset/confirm", null, confirm, 400);
            confirm = confirm.replace(token, newToken);
            call("POST", "/password-reset/confirm", null, confirm, 200);
            call("POST", "/password-reset/confirm", null, confirm, 400);
            call("GET", "/me", userToken, null, 401);
            call("POST", "/login", null, credentials(userEmail, PASSWORD), 401);
            login(userEmail, "New-password-456");
            System.out.println("PASS reset expiry, link replacement, single use, fresh password and session invalidation");
        } finally {
            api.stop();
            execute("DELETE FROM admin_audit WHERE actor_id = ?", admin);
            execute("DELETE FROM password_reset_requests WHERE user_id = ?", user);
            execute("DELETE FROM study_sessions WHERE exam_id = ?", exam);
            execute("DELETE FROM chapters WHERE exam_id = ?", exam);
            execute("DELETE FROM exams WHERE id = ?", exam);
            execute("DELETE FROM users WHERE id = ?", user);
            execute("DELETE FROM users WHERE id = ?", admin);
        }
    }

    private static long register(String email) throws Exception {
        return json(call("POST", "/register", null, "{\"name\":\"Test\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", 201)).get("id").getAsLong();
    }
    private static String credentials(String email, String password) { return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"; }
    private static String login(String email, String password) throws Exception { return json(call("POST", "/login", null, credentials(email, password), 200)).get("token").getAsString(); }
    private static JsonObject json(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }
    private static HttpResponse<String> call(String method, String path, String token, String body, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(ORIGIN + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == status, method + " " + path + " expected " + status + " got " + response.statusCode() + " " + response.body());
        return response;
    }
    private static void execute(String sql, long id) throws Exception {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) { statement.setLong(1, id); statement.executeUpdate(); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
