package backend.test;

import backend.model.Exam;
import backend.model.StudySession;
import backend.scheduler.Rescheduler;
import java.time.LocalDate;
import java.util.List;

public final class ReschedulerTest {
    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2026, 11, 14);
        Exam exam = new Exam(1, 1, "Exam", today.plusDays(3), 30);
        Rescheduler rescheduler = new Rescheduler();
        StudySession planned = session(1, today, 30, StudySession.Status.PLANNED);
        var unchanged = rescheduler.reschedule(exam, List.of(planned), planned, today);
        check(unchanged.replacements().isEmpty() && unchanged.unscheduledMinutes() == 0
                && planned.getStatus() == StudySession.Status.PLANNED, "Normal plan changed");
        System.out.println("PASS normal plan unchanged");

        StudySession missed = session(2, today.minusDays(1), 30, StudySession.Status.MISSED);
        StudySession completed = session(3, today.plusDays(1), 20, StudySession.Status.COMPLETED);
        String before = completed.toString();
        var fits = rescheduler.reschedule(exam, List.of(planned, missed, completed), missed, today);
        check(fits.unscheduledMinutes() == 0 && fits.replacements().size() == 2, "Expected two replacements");
        check(fits.replacements().get(0).getSessionDate().equals(today.plusDays(1))
                && fits.replacements().get(0).getPlannedMinutes() == 10
                && fits.replacements().get(1).getSessionDate().equals(today.plusDays(2))
                && fits.replacements().get(1).getPlannedMinutes() == 20, "Wrong replacement dates or minutes");
        check(missed.getSessionDate().equals(today.minusDays(1)) && missed.getPlannedMinutes() == 30,
                "Past missed session changed");
        System.out.println("PASS missed work fits across remaining days");

        exam.setExamDate(today.plusDays(2));
        var overflow = rescheduler.reschedule(exam, List.of(planned, missed, completed), missed, today);
        check(overflow.unscheduledMinutes() == 20 && overflow.replacements().size() == 1,
                "Expected 20 unscheduled minutes");
        System.out.println("PASS overflow returned as unscheduled");

        check(before.equals(completed.toString()), "Completed session changed");
        System.out.println("PASS completed session unchanged");
    }

    private static StudySession session(long id, LocalDate date, int minutes, StudySession.Status status) {
        return new StudySession(id, 1, 1, date, minutes, status, false);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
