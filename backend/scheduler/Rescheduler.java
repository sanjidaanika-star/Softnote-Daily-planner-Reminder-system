package backend.scheduler;

import backend.model.Exam;
import backend.model.StudySession;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Rescheduler {
    public record Result(List<StudySession> replacements, int unscheduledMinutes) {}

    public Result reschedule(Exam exam, List<StudySession> sessions, StudySession missed, LocalDate today) {
        if (missed.getStatus() != StudySession.Status.MISSED) {
            return new Result(List.of(), 0);
        }
        Map<LocalDate, Long> occupied = new HashMap<>();
        for (StudySession session : sessions) {
            if (session.getStatus() != StudySession.Status.MISSED) {
                occupied.merge(session.getSessionDate(), (long) session.getPlannedMinutes(), Long::sum);
            }
        }

        List<StudySession> replacements = new ArrayList<>();
        int remaining = missed.getPlannedMinutes();
        for (LocalDate date = today; date.isBefore(exam.getExamDate()) && remaining > 0; date = date.plusDays(1)) {
            long free = exam.getDailyMinutes() - occupied.getOrDefault(date, 0L);
            if (free > 0) {
                int minutes = (int) Math.min(free, remaining);
                replacements.add(new StudySession(0, exam.getId(), missed.getChapterId(), date,
                        minutes, StudySession.Status.PLANNED, missed.isRevision()));
                remaining -= minutes;
            }
        }
        return new Result(List.copyOf(replacements), remaining);
    }
}
