package backend.scheduler;

import backend.db.ChapterDAO;
import backend.db.ExamDAO;
import backend.db.SessionDAO;
import backend.model.Exam;
import backend.model.StudySession;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PlanService {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ExamDAO exams;
    private final ChapterDAO chapters;
    private final SessionDAO sessions;
    private final SchedulingStrategy strategy;
    private final Clock clock;
    private final Rescheduler rescheduler = new Rescheduler();

    public PlanService(ExamDAO exams, ChapterDAO chapters, SessionDAO sessions,
                       SchedulingStrategy strategy, Clock clock) {
        this.exams = exams;
        this.chapters = chapters;
        this.sessions = sessions;
        this.strategy = strategy;
        this.clock = clock;
    }

    public Future<List<StudySession>> generatePlan(long examId) {
        return executor.submit(() -> {
            Exam exam = exams.findById(examId).orElseThrow(() -> new NoSuchElementException("Exam not found"));
            List<StudySession> existing = sessions.findByExamId(examId);
            if (!existing.isEmpty()) {
                return existing;
            }
            List<StudySession> plan = strategy.generate(exam, chapters.findByExamId(examId), LocalDate.now(clock));
            return sessions.saveIfAbsent(examId, plan);
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                cancelPendingTasks();
            }
        } catch (InterruptedException exception) {
            cancelPendingTasks();
            Thread.currentThread().interrupt();
        }
    }

    public Future<StudySession> changeStatus(long sessionId, long userId, StudySession.Status status) {
        return executor.submit(() -> sessions.changeStatus(sessionId, userId, status,
                (exam, plan, missed) -> rescheduler.reschedule(exam, plan, missed, LocalDate.now(clock))));
    }

    private void cancelPendingTasks() {
        for (Runnable task : executor.shutdownNow()) {
            if (task instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }
}
