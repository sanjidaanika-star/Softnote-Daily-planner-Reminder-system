package backend.db;

import backend.model.StudySession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SessionDAO {
    private final DBConnection database;

    public SessionDAO(DBConnection database) {
        this.database = database;
    }

    public long insert(StudySession item) {
        String sql = """
                INSERT INTO study_sessions (exam_id, chapter_id, session_date, planned_minutes, status, is_revision)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create study session", exception);
        }
    }

    public List<StudySession> findByExamId(long examId) {
        String sql = """
                SELECT id, exam_id, chapter_id, session_date, planned_minutes, status, is_revision
                FROM study_sessions WHERE exam_id = ? ORDER BY id
                """;
        List<StudySession> items = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, examId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new StudySession(rows.getLong("id"),
                            rows.getLong("exam_id"),
                            rows.getLong("chapter_id"),
                            rows.getObject("session_date", LocalDate.class),
                            rows.getInt("planned_minutes"),
                            StudySession.Status.valueOf(rows.getString("status").toUpperCase(Locale.ROOT)),
                            rows.getBoolean("is_revision")));
                }
            }
            return items;
        } catch (SQLException exception) {
            throw new DataAccessException("Could not list study sessions", exception);
        }
    }
}
