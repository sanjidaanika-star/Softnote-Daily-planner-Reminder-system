package backend.db;

import backend.model.Exam;
import backend.model.StudySession;
import backend.scheduler.Rescheduler;
import backend.scheduler.SchedulingStrategy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

public final class SessionDAO {
    private final DBConnection database;

    public SessionDAO(DBConnection database) {
        this.database = database;
    }

    public long insert(StudySession item) {
        try (Connection connection = database.getConnection()) {
            return insert(connection, item);
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create study session", exception);
        }
    }

    static long insert(Connection connection, StudySession item) throws SQLException {
        String sql = """
                INSERT INTO study_sessions (exam_id, chapter_id, session_date, planned_minutes, status, is_revision)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, item.getExamId());
            statement.setLong(2, item.getChapterId());
            statement.setObject(3, item.getSessionDate());
            statement.setInt(4, item.getPlannedMinutes());
            statement.setString(5, item.getStatus().name().toLowerCase(Locale.ROOT));
            statement.setBoolean(6, item.isRevision());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Insert returned no study session ID");
            }
        }
    }

    public List<StudySession> saveIfAbsent(long examId, SchedulingStrategy strategy, LocalDate today) {
        String lockExam = "SELECT id, user_id, title, exam_date, daily_minutes FROM exams WHERE id = ? FOR UPDATE";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(lockExam)) {
                lock.setLong(1, examId);
                Exam exam;
                try (ResultSet rows = lock.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("Exam no longer exists");
                    }
                    exam = ExamDAO.readExam(rows);
                }
                List<StudySession> existing = findByExamId(connection, examId);
                if (!existing.isEmpty()) {
                    connection.commit();
                    return existing;
                }
                for (StudySession session : strategy.generate(exam, ChapterDAO.findByExamId(connection, examId), today)) {
                    insert(connection, session);
                }
                List<StudySession> saved = findByExamId(connection, examId);
                connection.commit();
                return saved;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not save study plan", exception);
        }
    }

    public record Unscheduled(String chapterName, int minutes) {}
    public record Plan(List<StudySession> sessions, List<Unscheduled> unscheduled) {}
    public record HistoryEntry(StudySession session, String chapterName, String examTitle) {}

    @FunctionalInterface
    public interface Reschedule {
        Rescheduler.Result apply(Exam exam, List<StudySession> plan, StudySession missed);
    }

    public StudySession changeStatus(long sessionId, long userId, StudySession.Status status, Boolean revision,
                                     Reschedule reschedule) {
        if (status != null && status != StudySession.Status.COMPLETED && status != StudySession.Status.MISSED) {
            throw new IllegalArgumentException("Status must be completed or missed");
        }
        String findExam = """
                SELECT e.id, e.title, e.exam_date, e.daily_minutes
                FROM exams e WHERE e.user_id = ?
                  AND e.id = (SELECT exam_id FROM study_sessions WHERE id = ?) FOR UPDATE
                """;
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Exam exam;
                try (PreparedStatement statement = connection.prepareStatement(findExam)) {
                    statement.setLong(1, userId);
                    statement.setLong(2, sessionId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new NoSuchElementException("Session not found");
                        }
                        exam = new Exam(rows.getLong("id"), userId, rows.getString("title"),
                                rows.getObject("exam_date", LocalDate.class), rows.getInt("daily_minutes"));
                    }
                }

                List<StudySession> plan = findByExamId(connection, exam.getId());
                StudySession session = plan.stream().filter(item -> item.getId() == sessionId)
                        .findFirst().orElseThrow(() -> new NoSuchElementException("Session not found"));
                StudySession.Status nextStatus = status == null ? session.getStatus() : status;
                boolean nextRevision = revision == null ? session.isRevision() : revision;
                if (session.getStatus() != nextStatus || session.isRevision() != nextRevision) {
                    if (session.getStatus() != StudySession.Status.PLANNED) {
                        throw new IllegalStateException("Only planned sessions can be edited");
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE study_sessions SET status = ?, is_revision = ? WHERE id = ?")) {
                        statement.setString(1, nextStatus.name().toLowerCase(Locale.ROOT));
                        statement.setBoolean(2, nextRevision);
                        statement.setLong(3, sessionId);
                        statement.executeUpdate();
                    }
                    session.setStatus(nextStatus);
                    session.setRevision(nextRevision);
                    if (nextStatus == StudySession.Status.MISSED) {
                        saveReschedule(connection, sessionId, reschedule.apply(exam, plan, session));
                    }
                }
                connection.commit();
                return session;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not update session status", exception);
        }
    }

    private static void saveReschedule(Connection connection, long sessionId, Rescheduler.Result result)
            throws SQLException {
        for (StudySession replacement : result.replacements()) {
            insert(connection, replacement);
        }
        if (result.unscheduledMinutes() > 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO unscheduled_sessions (session_id, minutes) VALUES (?, ?)")) {
                statement.setLong(1, sessionId);
                statement.setInt(2, result.unscheduledMinutes());
                statement.executeUpdate();
            }
        }
    }

    public Plan readPlan(long examId) {
        String sql = """
                SELECT c.name, u.minutes FROM unscheduled_sessions u
                JOIN study_sessions s ON s.id = u.session_id
                JOIN chapters c ON c.id = s.chapter_id
                WHERE s.exam_id = ? ORDER BY s.id
                """;
        try (Connection connection = database.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            List<StudySession> sessions = findByExamId(connection, examId);
            List<Unscheduled> unscheduled = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, examId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        unscheduled.add(new Unscheduled(rows.getString("name"), rows.getInt("minutes")));
                    }
                }
            }
            connection.commit();
            return new Plan(sessions, unscheduled);
        } catch (SQLException exception) {
            throw new DataAccessException("Could not read study plan", exception);
        }
    }

    public List<HistoryEntry> findHistory(long userId) {
        String sql = """
                SELECT s.*, c.name AS chapter_name, e.title AS exam_title FROM study_sessions s
                JOIN exams e ON e.id = s.exam_id
                JOIN chapters c ON c.id = s.chapter_id
                WHERE e.user_id = ? AND s.status IN ('completed', 'missed')
                ORDER BY s.session_date DESC, s.id DESC
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            List<HistoryEntry> history = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    history.add(new HistoryEntry(readSession(rows), rows.getString("chapter_name"),
                            rows.getString("exam_title")));
                }
            }
            return history;
        } catch (SQLException exception) {
            throw new DataAccessException("Could not read study history", exception);
        }
    }

    private static StudySession readSession(ResultSet rows) throws SQLException {
        return new StudySession(rows.getLong("id"), rows.getLong("exam_id"), rows.getLong("chapter_id"),
                rows.getObject("session_date", LocalDate.class), rows.getInt("planned_minutes"),
                StudySession.Status.valueOf(rows.getString("status").toUpperCase(Locale.ROOT)),
                rows.getBoolean("is_revision"));
    }

    public List<StudySession> findByExamId(long examId) {
        try (Connection connection = database.getConnection()) {
            return findByExamId(connection, examId);
        } catch (SQLException exception) {
            throw new DataAccessException("Could not list study sessions", exception);
        }
    }

    static List<StudySession> findByExamId(Connection connection, long examId) throws SQLException {
        String sql = """
                SELECT id, exam_id, chapter_id, session_date, planned_minutes, status, is_revision
                FROM study_sessions WHERE exam_id = ? ORDER BY session_date, id
                """;
        List<StudySession> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, examId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(readSession(rows));
                }
            }
            return items;
        }
    }
}
