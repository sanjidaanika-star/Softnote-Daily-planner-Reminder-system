package backend.model;

import java.time.LocalDate;

public final class StudySession {
    public enum Status {
        PLANNED, COMPLETED, MISSED
    }

    private long id;
    private long examId;
    private long chapterId;
    private LocalDate sessionDate;
    private int plannedMinutes;
    private Status status;
    private boolean revision;

    public StudySession(long id, long examId, long chapterId, LocalDate sessionDate,
                        int plannedMinutes, Status status, boolean revision) {
        setId(id);
        setExamId(examId);
        setChapterId(chapterId);
        setSessionDate(sessionDate);
        setPlannedMinutes(plannedMinutes);
        setStatus(status);
        setRevision(revision);
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

    public long getExamId() {
        return examId;
    }

    public void setExamId(long examId) {
        if (examId <= 0) {
            throw new IllegalArgumentException("Exam ID must be positive");
        }
        this.examId = examId;
    }

    public long getChapterId() {
        return chapterId;
    }

    public void setChapterId(long chapterId) {
        if (chapterId <= 0) {
            throw new IllegalArgumentException("Chapter ID must be positive");
        }
        this.chapterId = chapterId;
    }

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public void setSessionDate(LocalDate sessionDate) {
        if (sessionDate == null) {
            throw new IllegalArgumentException("Session date is required");
        }
        this.sessionDate = sessionDate;
    }

    public int getPlannedMinutes() {
        return plannedMinutes;
    }

    public void setPlannedMinutes(int plannedMinutes) {
        if (plannedMinutes <= 0) {
            throw new IllegalArgumentException("Planned minutes must be positive");
        }
        this.plannedMinutes = plannedMinutes;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        if (status == null) {
            throw new IllegalArgumentException("Status is required");
        }
        this.status = status;
    }

    public boolean isRevision() {
        return revision;
    }

    public void setRevision(boolean revision) {
        this.revision = revision;
    }

    @Override
    public String toString() {
        return "StudySession{" + "id=" + id
                + ", examId=" + examId
                + ", chapterId=" + chapterId
                + ", sessionDate=" + sessionDate
                + ", plannedMinutes=" + plannedMinutes
                + ", status=" + status
                + ", revision=" + revision + '}';
    }
}
