package backend.db;

import backend.ai.QuizValidator.Question;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class QuizDAO {
    public record Note(long id, String title) {}
    public record Answer(int chosenIndex, boolean is_correct, int correctIndex, String explanation) {}
    public record Quiz(long id, long noteId, int requestedCount, String status, String failureReason,
                       List<PublicQuestion> questions) {}
    public record PublicQuestion(long id, String question, List<String> options, Answer answer) {}
    public record Score(int correct, int totalAnswered, double percentage) {}

    private final DBConnection database;

    public QuizDAO(DBConnection database) {
        this.database = database;
    }

    public long saveNote(long userId, String title, String text) {
        return run(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO notes (user_id, title, extracted_text) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, userId);
                statement.setString(2, title);
                statement.setString(3, text);
                return insertId(statement);
            }
        });
    }

    public List<Note> listNotes(long userId) {
        return run(connection -> {
            List<Note> results = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT id, title FROM notes WHERE user_id=? ORDER BY id DESC")) {
                statement.setLong(1, userId);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) results.add(new Note(rows.getLong("id"), rows.getString("title")));
                }
            }
            return results;
        });
    }

    public String noteText(long userId, long noteId) {
        return run(connection -> {
            try (var statement = connection.prepareStatement("SELECT extracted_text FROM notes WHERE id=? AND user_id=?")) {
                statement.setLong(1, noteId);
                statement.setLong(2, userId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new NoSuchElementException("Note not found");
                    return rows.getString("extracted_text");
                }
            }
        });
    }

    public long create(long userId, long noteId, int count) {
        return run(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO quizzes (user_id, note_id, requested_count, status) VALUES (?, ?, ?, 'generating')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, userId);
                statement.setLong(2, noteId);
                statement.setInt(3, count);
                return insertId(statement);
            }
        });
    }

    public void ready(long quizId, List<Question> questions) {
        transaction(connection -> {
            try (var question = connection.prepareStatement("""
                    INSERT INTO quiz_questions (quiz_id, position, question_text, explanation, correct_index)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
                 var option = connection.prepareStatement(
                         "INSERT INTO quiz_options (question_id, position, option_text) VALUES (?, ?, ?)")) {
                for (int i = 0; i < questions.size(); i++) {
                    Question item = questions.get(i);
                    question.setLong(1, quizId);
                    question.setInt(2, i);
                    question.setString(3, item.question());
                    question.setString(4, item.explanation());
                    question.setInt(5, item.correctIndex());
                    long questionId = insertId(question);
                    for (int index = 0; index < item.options().size(); index++) {
                        option.setLong(1, questionId);
                        option.setInt(2, index);
                        option.setString(3, item.options().get(index));
                        option.addBatch();
                    }
                    option.executeBatch();
                }
            }
            setStatus(connection, quizId, "ready", null);
            return null;
        });
    }

    public void fail(long quizId, String reason) {
        run(connection -> setStatus(connection, quizId, "failed", reason));
    }

    private static int setStatus(Connection connection, long quizId, String status, String reason) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE quizzes SET status=?, failure_reason=? WHERE id=? AND status='generating'")) {
            statement.setString(1, status);
            statement.setString(2, reason);
            statement.setLong(3, quizId);
            return statement.executeUpdate();
        }
    }

    public Quiz read(long userId, long quizId) {
        return run(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT note_id, requested_count, status, failure_reason FROM quizzes WHERE id=? AND user_id=?")) {
                statement.setLong(1, quizId);
                statement.setLong(2, userId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new NoSuchElementException("Quiz not found");
                    String status = rows.getString("status");
                    return new Quiz(quizId, rows.getLong("note_id"), rows.getInt("requested_count"), status,
                            rows.getString("failure_reason"), "ready".equals(status)
                            ? publicQuestions(connection, userId, quizId) : List.of());
                }
            }
        });
    }

    private static List<PublicQuestion> publicQuestions(Connection connection, long userId, long quizId)
            throws SQLException {
        String sql = """
                SELECT q.id, q.question_text, q.correct_index, q.explanation, o.option_text,
                       a.chosen_index, a.is_correct
                FROM quiz_questions q JOIN quiz_options o ON o.question_id=q.id
                LEFT JOIN quiz_attempts a ON a.question_id=q.id AND a.quiz_id=q.quiz_id AND a.user_id=?
                WHERE q.quiz_id=? ORDER BY q.position, o.position
                """;
        Map<Long, PublicQuestion> questions = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setLong(2, quizId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("id");
                    PublicQuestion question = questions.get(id);
                    if (question == null) {
                        question = new PublicQuestion(id, rows.getString("question_text"), new ArrayList<>(), readAnswer(rows));
                        questions.put(id, question);
                    }
                    question.options().add(rows.getString("option_text"));
                }
            }
        }
        return List.copyOf(questions.values());
    }

    public Answer answer(long userId, long quizId, long questionId, int chosenIndex) {
        return transaction(connection -> {
            requireOwner(connection, userId, quizId, true);
            int correctIndex;
            String explanation;
            try (var statement = connection.prepareStatement("""
                    SELECT q.correct_index, q.explanation, a.chosen_index, a.is_correct
                    FROM quiz_questions q LEFT JOIN quiz_attempts a
                    ON a.question_id=q.id AND a.quiz_id=q.quiz_id AND a.user_id=?
                    WHERE q.id=? AND q.quiz_id=?
                    """)) {
                statement.setLong(1, userId);
                statement.setLong(2, questionId);
                statement.setLong(3, quizId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new NoSuchElementException("Question not found");
                    Answer existing = readAnswer(rows);
                    if (existing != null) return existing;
                    correctIndex = rows.getInt("correct_index");
                    explanation = rows.getString("explanation");
                }
            }
            boolean correct = chosenIndex == correctIndex;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO quiz_attempts (quiz_id, user_id, question_id, chosen_index, is_correct)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, quizId);
                statement.setLong(2, userId);
                statement.setLong(3, questionId);
                statement.setInt(4, chosenIndex);
                statement.setBoolean(5, correct);
                statement.executeUpdate();
            }
            return new Answer(chosenIndex, correct, correctIndex, explanation);
        });
    }

    public Score score(long userId, long quizId) {
        return run(connection -> {
            requireOwner(connection, userId, quizId, false);
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) AS answered, SUM(is_correct) AS correct FROM quiz_attempts WHERE quiz_id=? AND user_id=?")) {
                statement.setLong(1, quizId);
                statement.setLong(2, userId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    int answered = rows.getInt("answered");
                    int correct = rows.getInt("correct");
                    return new Score(correct, answered, answered == 0 ? 0 : Math.round(10000.0 * correct / answered) / 100.0);
                }
            }
        });
    }

    private static void requireOwner(Connection connection, long userId, long quizId, boolean answering)
            throws SQLException {
        String sql = answering ? "SELECT status FROM quizzes WHERE id=? AND user_id=? FOR UPDATE"
                : "SELECT status FROM quizzes WHERE id=? AND user_id=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, quizId);
            statement.setLong(2, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new NoSuchElementException("Quiz not found");
                if (answering && !"ready".equals(rows.getString("status"))) throw new IllegalStateException("Quiz is not ready.");
            }
        }
    }

    private static Answer readAnswer(ResultSet rows) throws SQLException {
        int chosenIndex = rows.getInt("chosen_index");
        return rows.wasNull() ? null : new Answer(chosenIndex, rows.getBoolean("is_correct"),
                rows.getInt("correct_index"), rows.getString("explanation"));
    }

    private static long insertId(PreparedStatement statement) throws SQLException {
        statement.executeUpdate();
        try (var rows = statement.getGeneratedKeys()) {
            if (rows.next()) return rows.getLong(1);
            throw new SQLException("Insert returned no ID");
        }
    }

    private <T> T run(SqlWork<T> work) {
        try (Connection connection = database.getConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw new DataAccessException("Could not access notes or quizzes", exception);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        return run(connection -> {
            connection.setAutoCommit(false);
            try {
                T value = work.execute(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
