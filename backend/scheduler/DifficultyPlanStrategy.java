package backend.scheduler;

import backend.model.Chapter;
import backend.model.Exam;
import backend.model.StudySession;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DifficultyPlanStrategy implements SchedulingStrategy {
    @Override
    public List<StudySession> generate(Exam exam, List<Chapter> chapters, LocalDate today) {
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
            targets[i] = (long) chapter.getEstimatedMinutes() * weight;
            total += targets[i];
        }
        if (total > capacity) {
            apportion(targets, total, capacity);
        }

        List<StudySession> sessions = new ArrayList<>();
        long usedMinutes = 0;
        int dailyLimit = exam.getDailyMinutes();
        for (int i = 0; i < chapters.size(); i++) {
            usedMinutes = append(sessions, chapters.get(i), targets[i], usedMinutes, today, dailyLimit, false);
        }
        long revisionBudget = capacity - usedMinutes;
        if (revisionBudget > 0) {
            if (revisionBudget < usedMinutes) {
                apportion(targets, usedMinutes, revisionBudget);
            }
            for (int i = 0; i < chapters.size(); i++) {
                usedMinutes = append(sessions, chapters.get(i), targets[i], usedMinutes, today, dailyLimit, true);
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

    private static long append(List<StudySession> sessions, Chapter chapter, long remaining,
                               long used, LocalDate today, int dailyLimit, boolean revision) {
        while (remaining > 0) {
            LocalDate date = today.plusDays(used / dailyLimit);
            int minutes = (int) Math.min(remaining, dailyLimit - used % dailyLimit);
            sessions.add(new StudySession(0, chapter.getExamId(), chapter.getId(), date,
                    minutes, StudySession.Status.PLANNED, revision));
            remaining -= minutes;
            used += minutes;
        }
        return used;
    }

    private record Remainder(int index, long value) {}
}
