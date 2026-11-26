package backend.test;

import backend.api.ApiServer;
import backend.db.DBConnection;
import backend.db.OnlineExamDAO;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class OnlineExamTest {
    private static final Gson JSON=new Gson();
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static DBConnection db;
    private static String origin;
    private static final class Time extends Clock {
        private volatile Instant now=Instant.parse("2027-01-01T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    public static void main(String[] args)throws Exception {
        db=new DBConnection(args.length==0?Path.of("config/database.test.properties"):Path.of(args[0]));
        check(count("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=DATABASE() AND schema_name=?","softnote_test")==1,"Only softnote_test is allowed");
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}origin="http://127.0.0.1:"+port;
        Time clock=new Time();var api=new ApiServer(db,port,clock);
        List<Long> accounts=new ArrayList<>(),papers=new ArrayList<>();
        api.start();
        try{
            String prefix="online-"+UUID.randomUUID();
            long admin=register(prefix+"-admin@example.test");accounts.add(admin);
            long user=register(prefix+"-student@example.test");accounts.add(user);
            long other=register(prefix+"-other@example.test");accounts.add(other);
            execute("UPDATE users SET role=? WHERE id=?","admin",admin);
            String a=login(prefix+"-admin@example.test"),u=login(prefix+"-student@example.test"),o=login(prefix+"-other@example.test");
            var body=paper(clock.instant().getEpochSecond());
            call("GET","/online-exams",null,null,401);
            call("POST","/admin/online-exams",u,body,403);
            Map<String,Object> invalid=new HashMap<>(body);invalid.put("durationMinutes",1.5);
            call("POST","/admin/online-exams",a,invalid,400);
            invalid=new HashMap<>(body);invalid.put("questions",List.of(Map.of("text","Invalid","options",List.of("A","B","C"),"correctIndex",0)));
            call("POST","/admin/online-exams",a,invalid,400);
            System.out.println("PASS authentication, admin-only publishing and question/time validation");

            var published=call("POST","/admin/online-exams",a,body,201).getAsJsonObject();long id=published.get("id").getAsLong();papers.add(id);
            check(count("SELECT COUNT(*) FROM reminders WHERE event_key=? AND user_id=?","online-exam:"+id,user)==1,"Student notification missing");
            check(count("SELECT COUNT(*) FROM reminders WHERE event_key=? AND user_id=?","online-exam:"+id,other)==1,"Other student notification missing");
            check(count("SELECT COUNT(*) FROM reminders WHERE event_key=? AND user_id=?","online-exam:"+id,admin)==0,"Admin should not be enrolled");
            check(!call("GET","/online-exams",u,null,200).toString().contains("correctIndex"),"List leaked answers");
            check(!call("GET","/online-exams/"+id,u,null,200).getAsJsonObject().has("questions"),"Unstarted exam leaked questions");
            call("POST","/online-exams/"+id+"/start",u,Map.of(),409);
            long late=register(prefix+"-late@example.test");accounts.add(late);String lateToken=login(prefix+"-late@example.test");
            call("GET","/online-exams/"+id,lateToken,null,404);
            call("POST","/online-exams/"+id+"/submit",lateToken,Map.of("answers",List.of(0,1)),404);
            call("GET","/admin/online-exams/"+id+"/report",u,null,403);
            System.out.println("PASS publication notifications, recipient snapshot, ownership and pre-start question privacy");

            clock.advance(11);
            var started=call("POST","/online-exams/"+id+"/start",u,Map.of(),200).getAsJsonObject();
            check(!started.toString().contains("correctIndex"),"Active attempt leaked correct answers");
            long deadline=started.get("deadline").getAsLong();
            check(call("POST","/online-exams/"+id+"/start",u,Map.of(),200).getAsJsonObject().get("deadline").getAsLong()==deadline,"Restart extended timer");
            call("PUT","/online-exams/"+id+"/answers",u,Map.of("answers",List.of(0)),400);
            call("PUT","/online-exams/"+id+"/answers",u,Map.of("answers",List.of(0,0)),200);
            check(call("GET","/online-exams/"+id,u,null,200).getAsJsonObject().getAsJsonArray("questions").get(0).getAsJsonObject().get("chosenIndex").getAsInt()==0,"Saved answer did not survive resume");
            call("POST","/online-exams/"+id+"/start",o,Map.of(),200);
            call("PUT","/online-exams/"+id+"/answers",o,Map.of("answers",List.of(1,0)),200);
            var workers=Executors.newFixedThreadPool(2);
            try{
                var first=workers.submit(()->call("POST","/online-exams/"+id+"/submit",u,Map.of("answers",List.of(0,0)),200));
                var second=workers.submit(()->call("POST","/online-exams/"+id+"/submit",u,Map.of("answers",List.of(0,0)),200));
                check(first.get().getAsJsonObject().get("score").getAsInt()==1 && second.get().getAsJsonObject().get("score").getAsInt()==1,"Concurrent submission was not idempotent");
            }finally{workers.shutdownNow();}
            check(call("POST","/online-exams/"+id+"/submit",u,Map.of("answers",List.of(0,1)),200).getAsJsonObject().get("score").getAsInt()==1,"Retry changed final answers");
            System.out.println("PASS server timer, hidden answers, autosave/resume, grading and concurrent single submission");

            clock.advance(61);new OnlineExamDAO(db,clock).expire();
            var expired=call("POST","/online-exams/"+id+"/submit",o,Map.of("answers",List.of(0,1)),200).getAsJsonObject();
            check(expired.get("score").getAsInt()==0 && expired.get("submittedAt").getAsLong()==deadline,"Expired attempt accepted late answers");
            var report=call("GET","/admin/online-exams/"+id+"/report",a,null,200).getAsJsonArray();
            check(report.asList().stream().filter(row->row.getAsJsonObject().has("submittedAt")).count()==2,"Report lost submissions");
            System.out.println("PASS server-side auto-submit without browser, late-answer rejection and admin report");

            var nearClose=paper(clock.instant().getEpochSecond());nearClose.put("opensAt",clock.instant().getEpochSecond()-1);nearClose.put("closesAt",clock.instant().getEpochSecond()+20);
            long shortId=call("POST","/admin/online-exams",a,nearClose,201).getAsJsonObject().get("id").getAsLong();papers.add(shortId);
            var shortStart=call("POST","/online-exams/"+shortId+"/start",u,Map.of(),200).getAsJsonObject();
            check(shortStart.get("deadline").getAsLong()==clock.instant().getEpochSecond()+20,"Duration ran beyond closing time");
            clock.advance(21);call("POST","/online-exams/"+shortId+"/start",o,Map.of(),409);
            System.out.println("PASS closing-time deadline cap and closed-exam start rejection");

            execute("CREATE TRIGGER online_exam_test_rollback BEFORE INSERT ON reminders FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Deliberate online exam rollback test'");
            try{
                long before=count("SELECT COUNT(*) FROM online_exams");
                call("POST","/admin/online-exams",a,paper(clock.instant().getEpochSecond()),500);
                check(count("SELECT COUNT(*) FROM online_exams")==before,"Failed notification left published paper");
                check(count("SELECT COUNT(*) FROM online_exam_attempts a LEFT JOIN online_exams e ON e.id=a.exam_id WHERE e.id IS NULL")==0,"Orphaned participation");
            }finally{execute("DROP TRIGGER IF EXISTS online_exam_test_rollback");}
            System.out.println("PASS publishing and notifications roll back together on database failure");
            System.out.println("OnlineExamTest: ALL PASSED (no external notifications)");
        }finally{
            api.stop();
            for(long id:papers){execute("DELETE FROM reminders WHERE event_key=?","online-exam:"+id);execute("DELETE FROM online_exam_attempts WHERE exam_id=?",id);execute("DELETE FROM online_exams WHERE id=?",id);}
            for(long id:accounts){execute("DELETE FROM admin_audit WHERE actor_id=? OR target_id=?",id,id);execute("DELETE FROM users WHERE id=?",id);}
        }
    }
    private static Map<String,Object> paper(long now){var map=new LinkedHashMap<String,Object>();map.put("title","Online QA exam");map.put("courseName","CSE 332");map.put("semester",5);map.put("opensAt",now+10);map.put("closesAt",now+3600);map.put("durationMinutes",1);map.put("questions",List.of(Map.of("text","Which keyword starts a Java class?","options",List.of("class","table","select","def"),"correctIndex",0),Map.of("text","How many options are shown?","options",List.of("Three","Four","Five","Six"),"correctIndex",1)));return map;}
    private static JsonElement call(String method,String path,String token,Object body,int expected)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15));if(token!=null)request.header("Authorization","Bearer "+token);if(body!=null)request.header("Content-Type","application/json");
        var response=HTTP.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==expected,path+" expected "+expected+", got "+response.statusCode()+": "+response.body());return JsonParser.parseString(response.body());
    }
    private static long register(String email)throws Exception{return call("POST","/register",null,Map.of("name","Online QA","email",email,"password","Online-QA-2027"),201).getAsJsonObject().get("id").getAsLong();}
    private static String login(String email)throws Exception{return call("POST","/login",null,Map.of("email",email,"password","Online-QA-2027"),200).getAsJsonObject().get("token").getAsString();}
    private static long count(String sql,Object...args)throws Exception{try(var c=db.getConnection();var s=c.prepareStatement(sql)){bind(s,args);try(var r=s.executeQuery()){r.next();return r.getLong(1);}}}
    private static void execute(String sql,Object...args)throws Exception{try(var c=db.getConnection();var s=c.prepareStatement(sql)){bind(s,args);s.executeUpdate();}}
    private static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
