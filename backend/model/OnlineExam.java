package backend.model;

import java.util.List;

/** Immutable published paper: answers stay exclusively on the server until submission. */
public record OnlineExam(String title, String courseName, int semester, long opensAt,
                         long closesAt, int durationMinutes, List<Question> questions) {
    public OnlineExam {
        title = text(title, "Exam title", 255);
        courseName = text(courseName, "Course name", 255);
        if (semester < 0 || semester > 12) throw new IllegalArgumentException("Semester must be 0 to 12");
        if (opensAt < 0 || closesAt <= opensAt || closesAt - opensAt > 30L * 86400)
            throw new IllegalArgumentException("Use a valid exam window of up to 30 days");
        if (durationMinutes < 1 || durationMinutes > 240)
            throw new IllegalArgumentException("Duration must be 1 to 240 minutes");
        if (questions == null || questions.isEmpty() || questions.size() > 50 || questions.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Add 1 to 50 MCQ questions");
        questions = List.copyOf(questions);
    }

    public record Question(String text, List<String> options, int correctIndex) {
        public Question {
            text = OnlineExam.text(text, "Question", 1000);
            if (options == null || options.size() != 4)
                throw new IllegalArgumentException("Each question needs four options");
            options = options.stream().map(option -> OnlineExam.text(option, "Option", 500)).toList();
            if (options.stream().distinct().count() != 4)
                throw new IllegalArgumentException("The four options must be different");
            if (correctIndex < 0 || correctIndex > 3)
                throw new IllegalArgumentException("Choose one correct option per question");
        }
    }

    private static String text(String value, String label, int limit) {
        if (value == null || value.isBlank() || value.strip().length() > limit)
            throw new IllegalArgumentException(label + " is required (maximum " + limit + " characters)");
        return value.strip();
    }
}
