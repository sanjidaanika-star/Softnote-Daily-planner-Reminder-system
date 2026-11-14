package backend.model;

import java.time.LocalDate;

public final class Exam {
    private long id;
    private long userId;
    private String title;
    private LocalDate examDate;
    private int dailyMinutes;

    public Exam(long id, long userId, String title, LocalDate examDate, int dailyMinutes) {
        setId(id);
        setUserId(userId);
        setTitle(title);
        setExamDate(examDate);
        setDailyMinutes(dailyMinutes);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("ID cannot be negative");
        }
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive");
        }
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title cannot be blank");
        }
        this.title = title;
    }

    public LocalDate getExamDate() {
        return examDate;
    }

    public void setExamDate(LocalDate examDate) {
        if (examDate == null) {
            throw new IllegalArgumentException("Exam date is required");
        }
        this.examDate = examDate;
    }

    public int getDailyMinutes() {
        return dailyMinutes;
    }

    public void setDailyMinutes(int dailyMinutes) {
        if (dailyMinutes <= 0) {
            throw new IllegalArgumentException("Daily minutes must be positive");
        }
        this.dailyMinutes = dailyMinutes;
    }

    @Override
    public String toString() {
        return "Exam{" + "id=" + id
                + ", userId=" + userId
                + ", title=" + title
                + ", examDate=" + examDate
                + ", dailyMinutes=" + dailyMinutes + '}';
    }
}
