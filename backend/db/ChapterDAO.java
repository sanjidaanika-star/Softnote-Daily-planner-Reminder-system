package backend.db;

import backend.model.Chapter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChapterDAO {
    private final DBConnection database;

    public ChapterDAO(DBConnection database) {
        this.database = database;
    }

    public long insert(Chapter item) {
        String sql = """
                INSERT INTO chapters (exam_id, name, difficulty, estimated_minutes)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, item.getExamId());
            statement.setString(2, item.getName());
            statement.setString(3, item.getDifficulty().name().toLowerCase(Locale.ROOT));
            statement.setInt(4, item.getEstimatedMinutes());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Insert returned no chapter ID");
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create chapter", exception);
        }
    }

    public List<Chapter> findByExamId(long examId) {
        String sql = """
                SELECT id, exam_id, name, difficulty, estimated_minutes
                FROM chapters WHERE exam_id = ? ORDER BY id
                """;
        List<Chapter> items = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, examId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new Chapter(rows.getLong("id"),
                            rows.getLong("exam_id"),
                            rows.getString("name"),
                            Chapter.Difficulty.valueOf(rows.getString("difficulty").toUpperCase(Locale.ROOT)),
                            rows.getInt("estimated_minutes")));
                }
            }
            return items;
        } catch (SQLException exception) {
            throw new DataAccessException("Could not list chapters", exception);
        }
    }
}
