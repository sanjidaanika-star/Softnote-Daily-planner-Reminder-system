package backend.test;

import backend.model.Chapter;
import backend.model.Exam;
import backend.model.StudySession;
import backend.scheduler.DifficultyPlanStrategy;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DifficultyPlanStrategyTest {
    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026, 11, 14);
        Exam exam = new Exam(1, 1, "Biology", today.plusDays(4), 60);
        List<Chapter> chapters = List.of(new Chapter(1, 1, "Cells", Chapter.Difficulty.EASY, 30),
                new Chapter(2, 1, "Genetics", Chapter.Difficulty.HARD, 30));

        DifficultyPlanStrategy strategy = new DifficultyPlanStrategy();
        List<StudySession> plan = strategy.generate(exam, chapters, today);
        Map<Long, Integer> main = new HashMap<>();
        Map<Long, Integer> totals = new HashMap<>();
        boolean revising = false;
        for (StudySession session : plan) {
            totals.merge(session.getChapterId(), session.getPlannedMinutes(), Integer::sum);
            if (session.isRevision()) {
                revising = true;
            } else {
                check(!revising, "Main study must precede revision");
                main.merge(session.getChapterId(), session.getPlannedMinutes(), Integer::sum);
            }
        }
        check(totals.get(2L) > totals.get(1L), "Hard chapter must receive more minutes");
        check(main.get(1L) == 30 && main.get(2L) == 90, "Expected the 1:3 difficulty ratio");
        System.out.printf("PASS difficulty weighting: easy=%d min, hard=%d min%n", totals.get(1L), totals.get(2L));
        checkDailyLimits(plan, exam, today);
        check(revising, "Expected revision during free time");
        System.out.println("PASS date window, daily limit, and revision order");

        exam.setExamDate(today.plusDays(1));
        List<StudySession> compressed = strategy.generate(exam, chapters, today);
        checkDailyLimits(compressed, exam, today);
        check(compressed.stream().mapToInt(StudySession::getPlannedMinutes).sum() == 60
                && compressed.stream().noneMatch(StudySession::isRevision), "Expected a full day of main study");
        check(strategy.generate(exam, chapters, exam.getExamDate()).isEmpty(), "Exam day must be excluded");
        System.out.println("PASS limited capacity and exam-day exclusion");
    }

    private static void checkDailyLimits(List<StudySession> sessions, Exam exam, LocalDate today) {
        Map<LocalDate, Integer> daily = new HashMap<>();
        for (StudySession session : sessions) {
            LocalDate date = session.getSessionDate();
            check(!date.isBefore(today) && date.isBefore(exam.getExamDate()), "Session outside study window");
            check(daily.merge(date, session.getPlannedMinutes(), Integer::sum) <= exam.getDailyMinutes(),
                    "Daily limit exceeded");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
