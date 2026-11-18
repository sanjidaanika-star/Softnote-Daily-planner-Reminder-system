package backend.api;

import backend.db.ReminderDAO;
import backend.db.ReminderDAO.Settings;
import backend.reminder.*;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;

final class ReminderRoutes {
    private final ReminderDAO dao;
    private final ReminderConfig config;
    private final Clock clock;
    private static final Gson JSON=new Gson();
    ReminderRoutes(ReminderDAO dao,ReminderConfig config,Clock clock){this.dao=dao;this.config=config;this.clock=clock;}
    void handle(HttpExchange exchange,long user)throws IOException {
        String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
        Object result;
        if("/reminders".equals(path)&&"GET".equals(method)) {
            var account=dao.account(user);
            result=Map.of("settings",account.settings(),"email",account.email(),"emailAvailable",config.emailReady(),
                    "pushAvailable",config.pushReady(),"publicKey",config.value("push.publicKey",""),
                    "unread",dao.unread(user),"items",dao.inbox(user));
        } else if("/reminders/settings".equals(path)&&"PUT".equals(method)) {
            Map<?,?> b=ApiServer.requestBody(exchange);
            Settings settings=new Settings(text(b,"dailyTime"),text(b,"timeZone"),flag(b,"dailyEnabled"),flag(b,"examEnabled"),flag(b,"emailEnabled"),flag(b,"pushEnabled"));
            if(settings.emailEnabled()) {
                if(!config.emailReady())throw new ApiServer.HttpError(503,"Email reminders need the sender service configuration. Contact the administrator.");
                String email=dao.account(user).email();
                if(email.endsWith(".test")||email.endsWith(".invalid")||email.endsWith("@example.com"))throw new IllegalArgumentException("Your account uses a test email address. Sign in with an account registered to your real email to receive reminders.");
            }
            if(settings.pushEnabled()&&!config.pushReady())throw new ApiServer.HttpError(503,"Browser push needs server configuration. Contact the administrator.");
            dao.save(user,settings);result=Map.of("message","Reminder settings saved");
        } else if("/reminders/subscription".equals(path)&&"POST".equals(method)) {
            if(!config.pushReady())throw new ApiServer.HttpError(503,"Browser push is not configured yet.");
            var b=ApiServer.requestBody(exchange);
            String endpoint=text(b,"endpoint"),key=text(b,"p256dh"),auth=text(b,"auth");
            WebPushSender.validateSubscription(endpoint,key,auth);
            dao.subscribe(user,endpoint,key,auth);result=Map.of("message","This browser can receive reminders");
        } else if("/reminders/subscription".equals(path)&&"DELETE".equals(method)) {
            dao.unsubscribe(user,text(ApiServer.requestBody(exchange),"endpoint"));result=Map.of("message","Browser disconnected");
        } else if("/reminders/test".equals(path)&&"POST".equals(method)) {
            ApiServer.requestBody(exchange);
            var account=dao.account(user);
            long id=dao.enqueue(account,"test:"+clock.instant().getEpochSecond()/60,"Softnote test reminder",
                    "Your reminders are connected. Future reminders will open your study plan.","/study.html");
            if(id==0)throw new ApiServer.HttpError(429,"A test was already requested. Wait one minute before trying again.");
            result=Map.of("message","Test reminder saved. Enabled channels will be processed within one minute.");
        } else if(path.matches("/reminders/[1-9][0-9]*/read")&&"PATCH".equals(method)) {
            ApiServer.requestBody(exchange);
            if(!dao.read(user,Long.parseLong(path.split("/")[2])))throw new ApiServer.HttpError(404,"Reminder not found");
            result=Map.of("message","Reminder marked as read");
        } else throw new ApiServer.HttpError(404,"Reminder endpoint not found");
        ApiServer.reply(exchange,200,JSON.toJson(result));
    }
    private static String text(Map<?,?> b,String key){if(!(b.get(key) instanceof String value))throw new IllegalArgumentException(key+" is required");return value;}
    private static boolean flag(Map<?,?> b,String key){if(!(b.get(key) instanceof Boolean value))throw new IllegalArgumentException(key+" must be true or false");return value;}
}
