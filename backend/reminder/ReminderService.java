package backend.reminder;

import backend.db.ReminderDAO;
import backend.db.ReminderDAO.Account;
import java.time.*;
import java.util.concurrent.*;

/** A persistent outbox supplies bounded retries and deduplication across server restarts. */
public final class ReminderService implements AutoCloseable {
    private final ReminderDAO dao;
    private final ReminderSender email,push;
    private final Clock clock;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"softnote-reminders"));
    public ReminderService(ReminderDAO dao,ReminderSender email,ReminderSender push,Clock clock) {
        this.dao=dao;this.email=email;this.push=push;this.clock=clock;
    }
    public void start(){timer.scheduleWithFixedDelay(this::safeTick,60,60,TimeUnit.SECONDS);}
    private void safeTick(){try{tick();}catch(RuntimeException error){System.err.println("Reminder scan could not finish. Check database configuration and phase10 migration.");}}
    public void tick(){
        for(Account account:dao.enabledAccounts()) {
            var settings=account.settings();
            ZonedDateTime now=clock.instant().atZone(ZoneId.of(settings.timeZone()));
            if(!isDue(now,LocalTime.parse(settings.dailyTime())))continue;
            LocalDate date=now.toLocalDate();
            if(settings.dailyEnabled()) {
                var sessions=dao.dailySessions(account.id(),date);
                if(!sessions.isEmpty()) {
                    int minutes=sessions.stream().mapToInt(s->((Number)s.get("minutes")).intValue()).sum();
                    String names=sessions.stream().limit(4).map(s->s.get("name").toString()).collect(java.util.stream.Collectors.joining(", "));
                    dao.enqueue(account,"daily:"+date,"Today's study reminder",minutes+" planned minutes remain today. Chapters: "+names+".","/study.html");
                }
            }
            if(settings.examEnabled()) for(var exam:dao.tomorrowExams(account.id(),date)) {
                dao.enqueue(account,"exam:"+exam.get("id")+":"+date,"Exam tomorrow",exam.get("title")+" is tomorrow. Review your study plan and remaining work.","/plan.html?examId="+exam.get("id"));
            }
        }
        deliver();
    }
    public static boolean isDue(ZonedDateTime now,LocalTime scheduled) {
        // Catch up a short outage, without sending stale notifications late at night.
        var due=now.toLocalDate().atTime(scheduled).atZone(now.getZone());
        var age=Duration.between(due,now);
        return !age.isNegative() && age.compareTo(Duration.ofHours(2))<0;
    }
    public void deliver(){
        for(var delivery:dao.due()) {
            if(Thread.currentThread().isInterrupted()) break;
            if(!dao.claim(delivery.id()))continue;
            try {
                if(!dao.allowed(delivery)){dao.cancelled(delivery.id());continue;}
                ("email".equals(delivery.channel())?email:push).send(delivery);
                dao.delivered(delivery.id());
            } catch(ReminderSender.DeliveryFailure error){dao.failed(delivery,error.getMessage(),error.expiredSubscription);}
            catch(RuntimeException error){dao.failed(delivery,"Reminder delivery could not finish.",false);}
        }
    }
    @Override public void close(){timer.shutdown();try{if(!timer.awaitTermination(20,TimeUnit.SECONDS))timer.shutdownNow();}catch(InterruptedException e){timer.shutdownNow();Thread.currentThread().interrupt();}}
}
