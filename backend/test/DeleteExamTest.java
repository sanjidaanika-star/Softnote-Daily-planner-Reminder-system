package backend.test;

import backend.api.ApiServer;
import backend.db.DBConnection;
import backend.db.ReminderDAO;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class DeleteExamTest {
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String ORIGIN = "http://127.0.0.1:18105";
    private static DBConnection db;
    public static void main(String[] args) throws Exception {
        db = new DBConnection(args.length == 0 ? Path.of("config/database.test.properties") : Path.of(args[0]));
        check(count("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=DATABASE() AND schema_name=?", "softnote_test") == 1, "Use softnote_test only");
        long user = 0, stranger = 0, exam = 0, kept = 0, note = 0, quiz = 0;
        ApiServer api = new ApiServer(db, 18105, Clock.systemUTC(), (text, amount) -> "{}");
        api.start();
        try {
            String email = "delete-" + UUID.randomUUID() + "@example.test";
            String otherEmail = "delete-other-" + UUID.randomUUID() + "@example.test";
            user = register(email); stranger = register(otherEmail);
            String token = login(email), otherToken = login(otherEmail);
            exam = create(token, "Disposable exam"); kept = create(token, "Keep this exam");
            var plan = call("GET", "/plan?examId=" + exam, token, null, 200);
            long session = plan.getAsJsonArray("sessions").get(0).getAsJsonObject().get("id").getAsLong();
            // Ensure history and overflow are also removed, not only future planned rows.
            execute("UPDATE study_sessions SET status=? WHERE id=?", "missed", session);
            execute("INSERT INTO unscheduled_sessions(session_id,minutes) VALUES(?,?)", session, 15);
            note = insert("INSERT INTO notes(user_id,title,extracted_text) VALUES(?,?,?)", user, "Keep notes", "Algorithms notes");
            quiz = insert("INSERT INTO quizzes(user_id,note_id,status,requested_count) VALUES(?,?,?,?)", user, note, "ready", 15);
            ReminderDAO reminders = new ReminderDAO(db);
            long reminder = reminders.enqueue(reminders.account(user), "delete-test", "Exam reminder", "Disposable exam", "/plan.html?examId=" + exam);
            execute("INSERT INTO reminder_deliveries(reminder_id,channel,target_id) VALUES(?,?,?)", reminder, "email", 0);

            call("DELETE", "/exams/" + exam, null, null, 401);
            call("DELETE", "/exams/" + exam, otherToken, null, 404);
            check(count("SELECT COUNT(*) FROM exams WHERE id=?", exam) == 1, "Foreign deletion changed nothing");
            System.out.println("PASS deletion requires login and exam ownership; other owners receive 404");

            // An extra FK deliberately fails the final DELETE to prove earlier deletes roll back.
            execute("CREATE TABLE IF NOT EXISTS exam_delete_test_guard (exam_id BIGINT PRIMARY KEY, FOREIGN KEY(exam_id) REFERENCES exams(id)) ENGINE=InnoDB");
            execute("INSERT INTO exam_delete_test_guard(exam_id) VALUES(?)", exam);
            call("DELETE", "/exams/" + exam, token, null, 500);
            check(count("SELECT COUNT(*) FROM study_sessions WHERE exam_id=?", exam) > 0, "Sessions rolled back");
            check(count("SELECT COUNT(*) FROM unscheduled_sessions WHERE session_id=?", session) == 1, "Overflow rolled back");
            check(count("SELECT COUNT(*) FROM reminders WHERE id=?", reminder) == 1, "Reminder rolled back");
            check(count("SELECT COUNT(*) FROM reminder_deliveries WHERE reminder_id=?", reminder) == 1, "Delivery rolled back");
            execute("DELETE FROM exam_delete_test_guard WHERE exam_id=?", exam);
            System.out.println("PASS failed delete rolls back sessions, overflow, chapters and reminder data");

            call("DELETE", "/exams/" + exam, token, null, 200);
            call("DELETE", "/exams/" + exam, token, null, 404);
            call("GET", "/exams/" + exam, token, null, 404);
            call("GET", "/plan?examId=" + exam, token, null, 404);
            call("GET", "/progress?examId=" + exam, token, null, 404);
            check(count("SELECT COUNT(*) FROM exams WHERE id=?", exam) == 0, "Exam deleted");
            check(count("SELECT COUNT(*) FROM chapters WHERE exam_id=?", exam) == 0, "Chapters deleted");
            check(count("SELECT COUNT(*) FROM study_sessions WHERE exam_id=?", exam) == 0, "All sessions deleted");
            check(count("SELECT COUNT(*) FROM unscheduled_sessions WHERE session_id=?", session) == 0, "Overflow deleted");
            check(count("SELECT COUNT(*) FROM reminders WHERE id=?", reminder) == 0, "Exam reminder deleted");
            check(count("SELECT COUNT(*) FROM reminder_deliveries WHERE reminder_id=?", reminder) == 0, "Reminder delivery deleted");
            call("GET", "/exams/" + kept, token, null, 200);
            check(count("SELECT COUNT(*) FROM notes WHERE id=?", note) == 1 && count("SELECT COUNT(*) FROM quizzes WHERE id=?", quiz) == 1, "Notes and quizzes preserved");
            System.out.println("PASS exam, plan, history, overflow and exam reminders removed; other exam, notes and quizzes preserved");
            System.out.println("DeleteExamTest: ALL PASSED");
        } finally {
            api.stop();
            execute("DROP TABLE IF EXISTS exam_delete_test_guard");
            execute("DELETE FROM quiz_attempts WHERE quiz_id=?", quiz);
            execute("DELETE FROM quizzes WHERE id=?", quiz);
            execute("DELETE FROM notes WHERE id=?", note);
            for (long id : new long[]{exam, kept}) {
                execute("DELETE FROM unscheduled_sessions WHERE session_id IN (SELECT id FROM study_sessions WHERE exam_id=?)", id);
                execute("DELETE FROM study_sessions WHERE exam_id=?", id);
                execute("DELETE FROM chapters WHERE exam_id=?", id);
                execute("DELETE FROM exams WHERE id=?", id);
            }
            execute("DELETE FROM users WHERE id=?", user); execute("DELETE FROM users WHERE id=?", stranger);
        }
    }
    private static JsonObject call(String method, String path, String token, String body, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(ORIGIN + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var response = HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == expected, path + " expected " + expected + ", got " + response.statusCode() + ": " + response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static long register(String email) throws Exception { return call("POST", "/register", null, JSON.toJson(Map.of("name", "Delete QA", "email", email, "password", "Delete-test-2026")), 201).get("id").getAsLong(); }
    private static String login(String email) throws Exception { return call("POST", "/login", null, JSON.toJson(Map.of("email", email, "password", "Delete-test-2026")), 200).get("token").getAsString(); }
    private static long create(String token, String title) throws Exception {
        return call("POST", "/exams", token, JSON.toJson(Map.of("title", title, "examDate", LocalDate.now().plusDays(4).toString(), "dailyMinutes", 30,
                "chapters", List.of(Map.of("name", "Graphs", "difficulty", "easy", "estimatedMinutes", 60)))), 201).get("id").getAsLong();
    }
    private static long count(String sql, Object... args) throws Exception { try (var c=db.getConnection(); var s=c.prepareStatement(sql)) { bind(s,args); try(var r=s.executeQuery()){r.next();return r.getLong(1);} } }
    private static void execute(String sql, Object... args) throws Exception { try(var c=db.getConnection();var s=c.prepareStatement(sql)){bind(s,args);s.execute();} }
    private static long insert(String sql, Object... args) throws Exception { try(var c=db.getConnection();var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)){bind(s,args);s.executeUpdate();try(var r=s.getGeneratedKeys()){r.next();return r.getLong(1);}} }
    private static void bind(PreparedStatement s,Object... args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
