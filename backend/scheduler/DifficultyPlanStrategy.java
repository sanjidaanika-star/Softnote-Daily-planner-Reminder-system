package backend.scheduler;

import backend.model.Chapter;
import backend.model.Exam;
import backend.model.StudySession;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DifficultyPlanStrategy implements SchedulingStrategy {
    @Override
    public List<StudySession> generate(Exam exam, List<Chapter> chapters, LocalDate today) {
        return generate(exam, chapters, today, List.of(), Map.of());
    }

    public List<StudySession> generate(Exam exam, List<Chapter> chapters, LocalDate today,
                                       List<StudySession> retained, Map<Long, Long> unscheduled) {
        long days = ChronoUnit.DAYS.between(today, exam.getExamDate());
        if (days <= 0 || chapters.isEmpty()) {
            return List.of();
        }

        long capacity = Math.min(days, Long.MAX_VALUE / exam.getDailyMinutes()) * exam.getDailyMinutes();
        long[] targets = new long[chapters.size()];
        long total = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            if (chapter.getId() <= 0 || chapter.getExamId() != exam.getId()) {
                throw new IllegalArgumentException("Chapters must be saved and belong to the exam");
            }
            int weight = switch (chapter.getDifficulty()) {
                case EASY -> 1;
                case MEDIUM -> 2;
                case HARD -> 3;
            };
            long credited = retained.stream()
                    .filter(session -> session.getChapterId() == chapter.getId() && !session.isRevision()
                            && session.getStatus() != StudySession.Status.MISSED)
                    .mapToLong(StudySession::getPlannedMinutes).sum();
            targets[i] = Math.max(0, (long) chapter.getEstimatedMinutes() * weight
                    - credited - unscheduled.getOrDefault(chapter.getId(), 0L));
            total += targets[i];
        }
        Map<LocalDate, Integer> occupied = new HashMap<>();
        int dailyLimit = exam.getDailyMinutes();
        for (StudySession session : retained) {
            LocalDate date = session.getSessionDate();
            if (!date.isBefore(today) && date.isBefore(exam.getExamDate())
                    && session.getStatus() != StudySession.Status.MISSED) {
                occupied.merge(date, Math.min(dailyLimit, session.getPlannedMinutes()),
                        (first, second) -> (int) Math.min(dailyLimit, (long) first + second));
            }
        }
        long available = capacity - occupied.values().stream().mapToLong(Integer::longValue).sum();
        if (total > available) {
            apportion(targets, total, available);
        }

        List<StudySession> sessions = new ArrayList<>();
        LocalDate date = today;
        long newStudyMinutes = 0;
        for (int i = 0; i < chapters.size(); i++) {
            date = append(sessions, chapters.get(i), targets[i], date, occupied, dailyLimit, false);
            newStudyMinutes += targets[i];
        }
        long revisionBudget = available - newStudyMinutes;
        if (revisionBudget > 0 && newStudyMinutes > 0) {
            if (revisionBudget < newStudyMinutes) {
                apportion(targets, newStudyMinutes, revisionBudget);
            }
            for (int i = 0; i < chapters.size(); i++) {
                date = append(sessions, chapters.get(i), targets[i], date, occupied, dailyLimit, true);
            }
        }
        return sessions;
    }

    private static void apportion(long[] targets, long total, long capacity) {
        BigInteger divisor = BigInteger.valueOf(total);
        BigInteger budget = BigInteger.valueOf(capacity);
        long unassigned = capacity;
        List<Remainder> remainders = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            BigInteger[] share = BigInteger.valueOf(targets[i]).multiply(budget).divideAndRemainder(divisor);
            targets[i] = share[0].longValueExact();
            unassigned -= targets[i];
            remainders.add(new Remainder(i, share[1].longValueExact()));
        }
        remainders.sort(Comparator.comparingLong(Remainder::value).reversed());
        for (int i = 0; i < unassigned; i++) {
            targets[remainders.get(i).index()]++;
        }
    }

    private static LocalDate append(List<StudySession> sessions, Chapter chapter, long remaining,
                                    LocalDate date, Map<LocalDate, Integer> occupied, int dailyLimit, boolean revision) {
        while (remaining > 0) {
            int used = occupied.getOrDefault(date, 0);
            if (used == dailyLimit) {
                date = date.plusDays(1);
                continue;
            }
            int minutes = (int) Math.min(remaining, dailyLimit - used);
            sessions.add(new StudySession(0, chapter.getExamId(), chapter.getId(), date,
                    minutes, StudySession.Status.PLANNED, revision));
            remaining -= minutes;
            occupied.put(date, used + minutes);
        }
        return date;
    }

    private record Remainder(int index, long value) {}
}
