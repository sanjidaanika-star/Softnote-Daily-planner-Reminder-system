package backend.db;

import backend.model.Chapter;
import backend.model.Exam;
import backend.model.StudySession;
import backend.scheduler.DifficultyPlanStrategy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

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
                INSERT INTO exams (user_id, title, exam_date, daily_minutes, semester, course_name, assessment)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, item.getUserId());
            statement.setString(2, item.getTitle());
            statement.setObject(3, item.getExamDate());
            statement.setInt(4, item.getDailyMinutes());
            statement.setInt(5, item.getSemester());
            statement.setString(6, item.getCourseName());
            statement.setString(7, item.getAssessment());
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

    public record ChapterEdit(long id, NewChapter details) {}

    public record ExamDetails(Exam exam, List<Chapter> chapters) {}

    public ExamDetails details(long examId, long userId) {
        return withLockedExam(examId, userId,
                (connection, exam) -> new ExamDetails(exam, ChapterDAO.findByExamId(connection, examId)));
    }

    public void update(Exam updated, List<ChapterEdit> edits, LocalDate today) {
        long examId = updated.getId();
        withLockedExam(examId, updated.getUserId(), (connection, current) -> {
            List<Chapter> original = ChapterDAO.findByExamId(connection, examId);
            Set<Long> chapterIds = new HashSet<>();
            original.forEach(chapter -> chapterIds.add(chapter.getId()));
            Set<Long> submitted = new HashSet<>();
            for (ChapterEdit edit : edits) {
                if (edit.id() != 0 && (!chapterIds.contains(edit.id()) || !submitted.add(edit.id()))) {
                    throw new IllegalArgumentException("Invalid or repeated chapter ID");
                }
            }
            boolean rebuild = current.getDailyMinutes() != updated.getDailyMinutes()
                    || !current.getExamDate().equals(updated.getExamDate())
                    || original.size() != edits.size();
            for (ChapterEdit edit : edits) {
                Chapter chapter = original.stream().filter(item -> item.getId() == edit.id()).findFirst().orElse(null);
                rebuild |= chapter == null || chapter.getDifficulty() != edit.details().difficulty()
                        || chapter.getEstimatedMinutes() != edit.details().estimatedMinutes();
            }
            if (rebuild && !updated.getExamDate().isAfter(today)) {
                throw new IllegalArgumentException("Choose a future exam date to rebuild the plan");
            }
            List<StudySession> retained = rebuild ? SessionDAO.findByExamId(connection, examId).stream()
                    .filter(session -> session.getStatus() != StudySession.Status.PLANNED
                            || session.getSessionDate().isBefore(today)).toList() : List.of();
            if (retained.stream().anyMatch(session -> !submitted.contains(session.getChapterId()))) {
                throw new IllegalStateException("Chapters with study history must stay in the exam");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE exams SET title = ?, exam_date = ?, daily_minutes = ?, semester = ?, course_name = ?, assessment = ? WHERE id = ?")) {
                statement.setString(1, updated.getTitle());
                statement.setObject(2, updated.getExamDate());
                statement.setInt(3, updated.getDailyMinutes());
                statement.setInt(4, updated.getSemester());
                statement.setString(5, updated.getCourseName());
                statement.setString(6, updated.getAssessment());
                statement.setLong(7, examId);
                statement.executeUpdate();
            }
            if (rebuild) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM study_sessions WHERE exam_id = ? AND status = 'planned' AND session_date >= ?")) {
                    statement.setLong(1, examId);
                    statement.setObject(2, today);
                    statement.executeUpdate();
                }
            }
            saveChapters(connection, examId, original, edits, submitted);
            if (rebuild) {
                for (StudySession session : new DifficultyPlanStrategy().generate(updated,
                        ChapterDAO.findByExamId(connection, examId), today, retained, readUnscheduled(connection, examId))) {
                    SessionDAO.insert(connection, session);
                }
            }
            return updated;
        });
    }

    private static Map<Long, Long> readUnscheduled(Connection connection, long examId) throws SQLException {
        String sql = """
                SELECT s.chapter_id, SUM(u.minutes) AS minutes
                FROM unscheduled_sessions u JOIN study_sessions s ON s.id = u.session_id
                WHERE s.exam_id = ? AND s.is_revision = FALSE GROUP BY s.chapter_id
                """;
        Map<Long, Long> minutes = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, examId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    minutes.put(rows.getLong("chapter_id"), rows.getLong("minutes"));
                }
            }
        }
        return minutes;
    }

    private static void saveChapters(Connection connection, long examId, List<Chapter> original,
                                     List<ChapterEdit> edits, Set<Long> submitted) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM chapters WHERE exam_id = ? AND id = ?");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE chapters SET name = ?, difficulty = ?, estimated_minutes = ? WHERE exam_id = ? AND id = ?")) {
            for (Chapter chapter : original) {
                if (!submitted.contains(chapter.getId())) {
                    delete.setLong(1, examId);
                    delete.setLong(2, chapter.getId());
                    delete.executeUpdate();
                }
            }
            for (ChapterEdit edit : edits) {
                NewChapter details = edit.details();
                Chapter chapter = new Chapter(edit.id(), examId, details.name(), details.difficulty(), details.estimatedMinutes());
                if (edit.id() == 0) {
                    ChapterDAO.insert(connection, chapter);
                    continue;
                }
                update.setString(1, chapter.getName());
                update.setString(2, chapter.getDifficulty().name().toLowerCase(Locale.ROOT));
                update.setInt(3, chapter.getEstimatedMinutes());
                update.setLong(4, examId);
                update.setLong(5, edit.id());
                update.executeUpdate();
            }
        }
    }

    private <T> T withLockedExam(long examId, long userId, ExamOperation<T> operation) {
        String sql = "SELECT * FROM exams WHERE id = ? AND user_id = ? FOR UPDATE";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(sql)) {
                lock.setLong(1, examId);
                lock.setLong(2, userId);
                Exam exam;
                try (ResultSet rows = lock.executeQuery()) {
                    if (!rows.next()) throw new NoSuchElementException("Exam not found");
                    exam = readExam(rows);
                }
                T result = operation.apply(connection, exam);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not access exam details", exception);
        }
    }

    @FunctionalInterface
    private interface ExamOperation<T> {
        T apply(Connection connection, Exam exam) throws SQLException;
    }

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
        String sql = "SELECT * FROM exams WHERE id = ?";
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

    static Exam readExam(ResultSet rows) throws SQLException {
        Exam exam = new Exam(rows.getLong("id"), rows.getLong("user_id"), rows.getString("title"),
                rows.getObject("exam_date", LocalDate.class), rows.getInt("daily_minutes"));
        exam.setSemester(rows.getInt("semester"));
        exam.setCourseName(rows.getString("course_name"));
        exam.setAssessment(rows.getString("assessment"));
        return exam;
    }

    public List<Exam> findByUserId(long userId) {
        String sql = """
                SELECT *
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
