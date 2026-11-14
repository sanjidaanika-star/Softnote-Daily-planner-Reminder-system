package backend.db;

import backend.model.Exam;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ExamDAO {
    private final DBConnection database;

    public ExamDAO(DBConnection database) {
        this.database = database;
    }

    public long insert(Exam item) {
        String sql = """
                INSERT INTO exams (user_id, title, exam_date, daily_minutes)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, item.getUserId());
            statement.setString(2, item.getTitle());
            statement.setObject(3, item.getExamDate());
            statement.setInt(4, item.getDailyMinutes());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Insert returned no exam ID");
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create exam", exception);
        }
    }

    public List<Exam> findByUserId(long userId) {
        String sql = """
                SELECT id, user_id, title, exam_date, daily_minutes
                FROM exams WHERE user_id = ? ORDER BY id
                """;
        List<Exam> items = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new Exam(rows.getLong("id"),
                            rows.getLong("user_id"),
                            rows.getString("title"),
                            rows.getObject("exam_date", LocalDate.class),
                            rows.getInt("daily_minutes")));
                }
            }
            return items;
        } catch (SQLException exception) {
            throw new DataAccessException("Could not list exams", exception);
        }
    }
}
