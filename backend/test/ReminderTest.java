package backend.test;

import backend.api.ApiServer;
import backend.db.*;
import backend.reminder.*;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReminderTest {
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static final Gson JSON=new Gson();
    private static DBConnection db;
    private static final String ORIGIN="http://127.0.0.1:18103";
    public static void main(String[] args)throws Exception {
        check(ReminderService.isDue(ZonedDateTime.parse("2026-11-18T20:00:00+06:00[Asia/Dhaka]"),LocalTime.of(20,0)),"Exact time due");
        check(!ReminderService.isDue(ZonedDateTime.parse("2026-11-18T19:59:00+06:00[Asia/Dhaka]"),LocalTime.of(20,0)),"Early reminder blocked");
        check(!ReminderService.isDue(ZonedDateTime.parse("2026-11-18T22:00:00+06:00[Asia/Dhaka]"),LocalTime.of(20,0)),"Stale reminder blocked");
        new ReminderDAO.Settings("20:00","Asia/Dhaka",true,true,true,true);
        rejects(()->new ReminderDAO.Settings("25:00","Asia/Dhaka",false,false,false,false));
        rejects(()->new ReminderDAO.Settings("20:00","Unknown/Zone",false,false,false,false));
        rejects(()->WebPushSender.validateSubscription("http://127.0.0.1:8080/internal","bad","bad"));
        rejects(()->WebPushSender.validateSubscription("https://fcm.googleapis.com.evil.test/x","bad","bad"));
        System.out.println("PASS timezone scheduling, catch-up window, invalid settings and push SSRF rejection");
        pushEncryption();

        db=new DBConnection(args.length==0?Path.of("config/database.test.properties"):Path.of(args[0]));
        try(var c=db.getConnection();var s=c.prepareStatement("SELECT DATABASE()=?")){
            s.setString(1,"softnote_test");try(var r=s.executeQuery()){check(r.next()&&r.getBoolean(1),"Use softnote_test only");}
        }
        long user=0,other=0,exam=0;
        var date=LocalDate.now(ZoneId.of("Asia/Dhaka"));
        Clock clock=Clock.fixed(date.atTime(20,30).atZone(ZoneId.of("Asia/Dhaka")).toInstant(),ZoneOffset.UTC);
        ApiServer server=new ApiServer(db,18103,clock,(text,count)->"{}");
        server.start();
        try {
            String email="reminder-"+UUID.randomUUID()+"@example.test";
            String another="other-"+UUID.randomUUID()+"@example.test";
            user=register(email);other=register(another);
            String token=login(email),otherToken=login(another);
            call("GET","/reminders",null,null,401);
            JsonObject initial=call("GET","/reminders",token,null,200);
            check(initial.getAsJsonArray("items").isEmpty(),"Empty inbox");
            check(!initial.toString().contains("privateKey")&&!initial.toString().contains("smtp.password"),"Secrets stay private");
            var valid=new ReminderDAO.Settings("20:00","Asia/Dhaka",true,true,false,false);
            call("PUT","/reminders/settings",token,JSON.toJson(valid),200);
            call("PUT","/reminders/settings",token,JSON.toJson(valid).replace("Asia/Dhaka","Unknown/Zone"),400);
            call("POST","/reminders/test",token,"{}",200);
            call("POST","/reminders/test",token,"{}",429);
            long reminder=call("GET","/reminders",token,null,200).getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsLong();
            check(call("GET","/reminders",otherToken,null,200).getAsJsonArray("items").isEmpty(),"Other account sees no reminders");
            call("PATCH","/reminders/"+reminder+"/read",otherToken,"{}",404);
            call("PATCH","/reminders/"+reminder+"/read",token,"{}",200);
            check(call("GET","/reminders",token,null,200).get("unread").getAsInt()==0,"Read state saved");
            System.out.println("PASS authenticated settings, inbox ownership, read ownership, secret exclusion and test rate limit");

            try(var c=db.getConnection();var s=c.prepareStatement("INSERT INTO exams(user_id,title,exam_date,daily_minutes) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                s.setLong(1,user);s.setString(2,"Algorithms Mid");s.setObject(3,date.plusDays(1));s.setInt(4,30);s.executeUpdate();try(var r=s.getGeneratedKeys()){r.next();exam=r.getLong(1);}
            }
            long chapter;
            try(var c=db.getConnection();var s=c.prepareStatement("INSERT INTO chapters(exam_id,name,difficulty,estimated_minutes) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                s.setLong(1,exam);s.setString(2,"Graphs");s.setString(3,"easy");s.setInt(4,30);s.executeUpdate();try(var r=s.getGeneratedKeys()){r.next();chapter=r.getLong(1);}
            }
            execute("INSERT INTO study_sessions(exam_id,chapter_id,session_date,planned_minutes,status,is_revision) VALUES(?,?,?,?,?,?)",exam,chapter,date,30,"planned",false);
            var dao=new ReminderDAO(db);
            var settings=new ReminderDAO.Settings("20:00","Asia/Dhaka",true,true,true,true);
            dao.save(user,settings);
            // Fake transport and fake endpoint used only by this database test. No external send.
            dao.subscribe(user,"https://fcm.googleapis.com/fake-test","fake-key","fake-auth");
            AtomicInteger emails=new AtomicInteger(),pushes=new AtomicInteger();
            try(var service=new ReminderService(dao,d->emails.incrementAndGet(),d->pushes.incrementAndGet(),clock)){
                service.tick();service.tick();
                check(emails.get()==2&&pushes.get()==2,"One daily and one exam reminder, once per channel");
                check(dao.inbox(user).size()==3,"Only three events including earlier test");
                execute("UPDATE study_sessions SET status=? WHERE exam_id=?","completed",exam);
                check(dao.dailySessions(user,date).isEmpty(),"Completed work excluded");
                dao.enqueue(dao.account(user),"cancel","Cancel test","Disabled channels should not send","/study.html");
                dao.save(user,new ReminderDAO.Settings("20:00","Asia/Dhaka",true,true,false,false));
                service.deliver();check(emails.get()==2&&pushes.get()==2,"Opt-out cancels pending deliveries");
            }
            System.out.println("PASS daily + exam reminders, persistent deduplication, completed exclusion and opt-out cancellation");

            dao.save(user,settings);
            dao.enqueue(dao.account(user),"retry","Retry test","Transport fails safely","/study.html");
            AtomicInteger attempts=new AtomicInteger();
            try(var failing=new ReminderService(dao,d->{attempts.incrementAndGet();throw new ReminderSender.DeliveryFailure("Temporary email failure");},d->{throw new ReminderSender.DeliveryFailure("Expired",true);},clock)){
                failing.deliver();
                check(attempts.get()==1,"First email attempt failed");
                check(dao.due().isEmpty(),"Retry is delayed");
                for(int i=0;i<3;i++){
                    execute("UPDATE reminder_deliveries SET next_attempt=CURRENT_TIMESTAMP-INTERVAL 1 MINUTE WHERE reminder_id IN (SELECT id FROM reminders WHERE user_id=?)",user);
                    failing.deliver();
                }
                check(attempts.get()==3,"Only three attempts allowed");
                check(dao.due().isEmpty(),"No infinite retry");
            }
            check(dao.inbox(user).stream().anyMatch(row->"failed".equals(row.get("emailStatus"))),"Failure appears in inbox");
            System.out.println("PASS fake sender failure, delayed retry, three-attempt limit, expired push and visible failure status");
            System.out.println("ReminderTest: ALL PASSED (no external email or push calls)");
        } finally {
            server.stop();
            execute("DELETE FROM study_sessions WHERE exam_id=?",exam);
            execute("DELETE FROM chapters WHERE exam_id=?",exam);
            execute("DELETE FROM exams WHERE id=?",exam);
            execute("DELETE FROM users WHERE id=?",user);execute("DELETE FROM users WHERE id=?",other);
        }
    }
    private static JsonObject call(String method,String path,String token,String body,int expected)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(ORIGIN+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        var response=HTTP.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==expected,path+" expected "+expected+", got "+response.statusCode()+": "+response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static long register(String email)throws Exception{return call("POST","/register",null,JSON.toJson(Map.of("name","Reminder QA","email",email,"password","Reminder-test-123")),201).get("id").getAsLong();}
    private static String login(String email)throws Exception{return call("POST","/login",null,JSON.toJson(Map.of("email",email,"password","Reminder-test-123")),200).get("token").getAsString();}
    private static void execute(String sql,Object...args)throws Exception{try(var c=db.getConnection();var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.executeUpdate();}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Expected validation error");}catch(IllegalArgumentException expected){}}
    private static void pushEncryption() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("ECDH","BC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("prime256v1"));
        var server=generator.generateKeyPair();var browser=generator.generateKeyPair();
        var encoder=Base64.getUrlEncoder().withoutPadding();
        String serverPublic=encoder.encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey)server.getPublic()));
        String browserPublic=encoder.encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPublicKey)browser.getPublic()));
        Properties p=new Properties();p.setProperty("push.publicKey",serverPublic);
        p.setProperty("push.privateKey",encoder.encodeToString(nl.martijndwars.webpush.Utils.encode((org.bouncycastle.jce.interfaces.ECPrivateKey)server.getPrivate())));
        p.setProperty("push.subject","mailto:test@example.test");
        var delivery=new ReminderDAO.Delivery(1,1,"push",1,1,"test@example.test","Study reminder","Graphs, 30 min","/study.html",
                "https://fcm.googleapis.com/fake",browserPublic,encoder.encodeToString(new byte[16]));
        var request=new WebPushSender(new ReminderConfig(p)).prepare(delivery);
        check(request.headers().firstValue("Content-Encoding").orElse("").equals("aes128gcm"),"Standards-based encrypted push");
        check(request.headers().firstValue("Authorization").orElse("").startsWith("vapid "),"VAPID authorization generated");
        check(request.bodyPublisher().isPresent(),"Encrypted body supplied");
        System.out.println("PASS AES128GCM payload and VAPID signing with generated keys (no network call)");
    }
}
