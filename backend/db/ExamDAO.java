package backend.db;

import backend.model.Chapter;
import backend.model.Exam;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ExamDAO {
    private final DBConnection database;

    public ExamDAO(DBConnection database) {
        this.database = database;
    }

    public long insert(Exam item) {
        try (Connection connection = database.getConnection()) {
            return insert(connection, item);
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create exam", exception);
        }
    }

    private static long insert(Connection connection, Exam item) throws SQLException {
        String sql = """
                INSERT INTO exams (user_id, title, exam_date, daily_minutes)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
        }
    }

    public record NewChapter(String name, Chapter.Difficulty difficulty, int estimatedMinutes) {}

    public long insertWithChapters(Exam exam, List<NewChapter> chapters) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long examId = insert(connection, exam);
                for (NewChapter chapter : chapters) {
                    ChapterDAO.insert(connection, new Chapter(0, examId, chapter.name(),
                            chapter.difficulty(), chapter.estimatedMinutes()));
                }
                connection.commit();
                return examId;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create exam with chapters", exception);
        }
    }

    public Optional<Exam> findById(long examId) {
        String sql = "SELECT id, user_id, title, exam_date, daily_minutes FROM exams WHERE id = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, examId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readExam(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not find exam", exception);
        }
    }

    private static Exam readExam(ResultSet rows) throws SQLException {
        return new Exam(rows.getLong("id"), rows.getLong("user_id"), rows.getString("title"),
                rows.getObject("exam_date", LocalDate.class), rows.getInt("daily_minutes"));
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
                    items.add(readExam(rows));
                }
            }
            return items;
        } catch (SQLException exception) {
            throw new DataAccessException("Could not list exams", exception);
        }
    }
}
