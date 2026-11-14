package backend.model;

public final class Chapter {
    public enum Difficulty {
        EASY, MEDIUM, HARD
    }

    private long id;
    private long examId;
    private String name;
    private Difficulty difficulty;
    private int estimatedMinutes;

    public Chapter(long id, long examId, String name, Difficulty difficulty, int estimatedMinutes) {
        setId(id);
        setExamId(examId);
        setName(name);
        setDifficulty(difficulty);
        setEstimatedMinutes(estimatedMinutes);
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be blank");
        }
        this.name = name;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        if (difficulty == null) {
            throw new IllegalArgumentException("Difficulty is required");
        }
        this.difficulty = difficulty;
    }

    public int getEstimatedMinutes() {
        return estimatedMinutes;
    }

    public void setEstimatedMinutes(int estimatedMinutes) {
        if (estimatedMinutes <= 0) {
            throw new IllegalArgumentException("Estimated minutes must be positive");
        }
        this.estimatedMinutes = estimatedMinutes;
    }

    @Override
    public String toString() {
        return "Chapter{" + "id=" + id
                + ", examId=" + examId
                + ", name=" + name
                + ", difficulty=" + difficulty
                + ", estimatedMinutes=" + estimatedMinutes + '}';
    }
}
