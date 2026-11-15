package backend.scheduler;

import backend.db.SessionDAO;
import backend.model.StudySession;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PlanService {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final SessionDAO sessions;
    private final SchedulingStrategy strategy;
    private final Clock clock;
    private final Rescheduler rescheduler = new Rescheduler();

    public PlanService(SessionDAO sessions, SchedulingStrategy strategy, Clock clock) {
        this.sessions = sessions;
        this.strategy = strategy;
        this.clock = clock;
    }

    public Future<List<StudySession>> generatePlan(long examId) {
        return executor.submit(() -> {
            List<StudySession> existing = sessions.findByExamId(examId);
            if (!existing.isEmpty()) {
                return existing;
            }
            return sessions.saveIfAbsent(examId, strategy, LocalDate.now(clock));
        });
    }

    public void executeBackground(Runnable work) {
        executor.execute(work);
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

    public Future<StudySession> changeStatus(long sessionId, long userId, StudySession.Status status, Boolean revision) {
        return executor.submit(() -> sessions.changeStatus(sessionId, userId, status, revision,
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
