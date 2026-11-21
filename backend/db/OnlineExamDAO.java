package backend.db;

import backend.model.OnlineExam;
import backend.model.OnlineExam.Question;
import com.google.gson.Gson;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/** Published papers are immutable. Participation, publication and grading are transactional. */
public final class OnlineExamDAO {
    private static final Gson JSON = new Gson();
    private final DBConnection database;
    private final Clock clock;
    public OnlineExamDAO(DBConnection database, Clock clock) { this.database=database; this.clock=clock; }

    public Map<String,Object> publish(long admin, OnlineExam paper) {
        if (paper.closesAt() <= clock.instant().getEpochSecond())
            throw new IllegalArgumentException("The exam closing time must be in the future");
        return transaction(c -> {
            long id;
            try (var s=c.prepareStatement("INSERT INTO online_exams(created_by,title,course_name,semester,opens_at,closes_at,duration_minutes,questions_json) VALUES(?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                bind(s,admin,paper.title(),paper.courseName(),paper.semester(),paper.opensAt(),paper.closesAt(),paper.durationMinutes(),JSON.toJson(paper.questions()));
                s.executeUpdate(); try(var r=s.getGeneratedKeys()){r.next();id=r.getLong(1);}
            }
            int count=update(c,"INSERT INTO online_exam_attempts(exam_id,user_id,answers_json) SELECT ?,id,? FROM users WHERE role=? AND active=?",id,"[]","user",true);
            String key="online-exam:"+id, path="/online-exams.html?examId="+id;
            update(c,"INSERT INTO reminders(user_id,event_key,title,message,target_path) SELECT user_id,?,?,?,? FROM online_exam_attempts WHERE exam_id=?",
                    key,"New exam: "+paper.title().substring(0,Math.min(240,paper.title().length())),paper.courseName()+" — open Online exams to see the start time and duration.",path,id);
            update(c,"INSERT INTO reminder_deliveries(reminder_id,channel,target_id) SELECT r.id,?,? FROM reminders r JOIN reminder_settings s ON s.user_id=r.user_id WHERE r.event_key=? AND s.email_enabled=?","email",0,key,true);
            update(c,"INSERT INTO reminder_deliveries(reminder_id,channel,target_id) SELECT r.id,?,p.id FROM reminders r JOIN reminder_settings s ON s.user_id=r.user_id JOIN push_subscriptions p ON p.user_id=r.user_id WHERE r.event_key=? AND s.push_enabled=?","push",key,true);
            update(c,"INSERT INTO admin_audit(actor_id,target_id,action) VALUES(?,?,?)",admin,admin,"Published online exam #"+id);
            return Map.of("id",id,"notifiedStudents",count);
        });
    }

    public List<Map<String,Object>> list(long user, boolean admin) {
        String sql=admin ? "SELECT e.*,NULL AS started_at,NULL AS submitted_at,NULL AS score FROM online_exams e ORDER BY e.id DESC LIMIT ?"
                : "SELECT e.*,a.started_at,a.submitted_at,a.score FROM online_exams e JOIN online_exam_attempts a ON a.exam_id=e.id WHERE a.user_id=? ORDER BY e.id DESC LIMIT ?";
        try(var c=database.getConnection();var s=c.prepareStatement(sql)) {
            if(admin)bind(s,200);else bind(s,user,200);
            try(var r=s.executeQuery()) {
                List<Map<String,Object>> items=new ArrayList<>();
                while(r.next()) {
                    Map<String,Object> item=metadata(r);
                    item.put("questionCount",questions(r.getString("questions_json")).size());
                    item.put("started",r.getObject("started_at")!=null);
                    item.put("submitted",r.getObject("submitted_at")!=null);
                    item.put("score",r.getObject("score"));items.add(item);
                } return items;
            }
        } catch(SQLException failure){throw new DataAccessException("Could not list online exams",failure);}
    }

    /** action: view, start, save or submit. The locked row makes submit/retry and deadline races safe. */
    public Map<String,Object> attempt(long user,long exam,String action,List<Integer> answers) {
        return transaction(c -> {
            Map<String,Object> data;
            List<Question> paper;
            Long started,deadline,submitted;
            List<Integer> saved;
            try(var s=c.prepareStatement("SELECT * FROM online_exam_attempts WHERE exam_id=? AND user_id=? FOR UPDATE")) {
                bind(s,exam,user);try(var r=s.executeQuery()){
                    if(!r.next())throw new NoSuchElementException("Online exam not found");
                    started=(Long)r.getObject("started_at");deadline=(Long)r.getObject("deadline");submitted=(Long)r.getObject("submitted_at");
                    saved=new ArrayList<>(Arrays.asList(JSON.fromJson(r.getString("answers_json"),Integer[].class)));
                }
            }
            try(var s=c.prepareStatement("SELECT * FROM online_exams WHERE id=?")) {
                bind(s,exam);try(var r=s.executeQuery()){if(!r.next())throw new NoSuchElementException("Online exam not found");data=metadata(r);paper=questions(r.getString("questions_json"));}
            }
            long now=clock.instant().getEpochSecond(),opens=((Number)data.get("opensAt")).longValue(),closes=((Number)data.get("closesAt")).longValue();
            if(started!=null && submitted==null && now>=deadline) {
                finish(c,exam,user,saved,paper,deadline);submitted=deadline;
            }
            if("start".equals(action) && started==null) {
                if(now<opens || now>=closes)throw new IllegalStateException("This exam is not open");
                started=now;deadline=Math.min(closes,now+((Number)data.get("durationMinutes")).longValue()*60);
                saved=new ArrayList<>(Collections.nCopies(paper.size(),null));
                update(c,"UPDATE online_exam_attempts SET started_at=?,deadline=?,answers_json=? WHERE exam_id=? AND user_id=?",started,deadline,JSON.toJson(saved),exam,user);
            }
            if("save".equals(action) || "submit".equals(action)) {
                if(started==null)throw new IllegalStateException("Start the exam before answering");
                if(submitted==null) {
                    if(answers!=null) {
                        if(answers.size()!=paper.size() || answers.stream().anyMatch(a -> a!=null&&(a<0||a>3)))
                            throw new IllegalArgumentException("Supply one answer (0 to 3 or null) per question");
                        saved=new ArrayList<>(answers);
                        update(c,"UPDATE online_exam_attempts SET answers_json=? WHERE exam_id=? AND user_id=?",JSON.toJson(saved),exam,user);
                    }
                    if("submit".equals(action)){finish(c,exam,user,saved,paper,now);submitted=now;}
                }
            }
            data.put("serverNow",now);data.put("startedAt",started);data.put("deadline",deadline);data.put("submittedAt",submitted);
            data.put("questionCount",paper.size());
            if(started!=null) {
                List<Map<String,Object>> safe=new ArrayList<>();
                for(int i=0;i<paper.size();i++) {
                    var q=paper.get(i); Map<String,Object> question=new LinkedHashMap<>();
                    question.put("text",q.text());question.put("options",q.options());
                    question.put("chosenIndex",i<saved.size()?saved.get(i):null);
                    if(submitted!=null)question.put("correctIndex",q.correctIndex());safe.add(question);
                }
                data.put("questions",safe);
            }
            if(submitted!=null){int score=score(saved,paper);data.put("score",score);data.put("percentage",score*100.0/paper.size());}
            return data;
        });
    }

    public List<Map<String,Object>> report(long exam) {
        List<Long> users=transaction(c -> {
            try(var exists=c.prepareStatement("SELECT id FROM online_exams WHERE id=?")){bind(exists,exam);try(var r=exists.executeQuery()){if(!r.next())throw new NoSuchElementException("Online exam not found");}}
            return ids(c,"SELECT user_id FROM online_exam_attempts WHERE exam_id=?",exam);
        });
        // Materialize expired attempts before reporting; also performed by the background grader.
        for(long user:users)attempt(user,exam,"view",null);
        try(var c=database.getConnection();var s=c.prepareStatement("SELECT u.id,u.name,u.email,a.started_at AS startedAt,a.deadline,a.submitted_at AS submittedAt,a.score FROM online_exam_attempts a JOIN users u ON u.id=a.user_id WHERE a.exam_id=? ORDER BY a.submitted_at IS NULL,u.name,u.id")) {
            bind(s,exam);try(var r=s.executeQuery()){
                List<Map<String,Object>> rows=new ArrayList<>();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=7;i++)row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));rows.add(row);}return rows;
            }
        } catch(SQLException failure){throw new DataAccessException("Could not load exam submissions",failure);}
    }

    public void expire() {
        List<long[]> due=transaction(c -> {
            try(var s=c.prepareStatement("SELECT user_id,exam_id FROM online_exam_attempts WHERE submitted_at IS NULL AND deadline<=? LIMIT ?")){
                bind(s,clock.instant().getEpochSecond(),100);try(var r=s.executeQuery()){List<long[]> result=new ArrayList<>();while(r.next())result.add(new long[]{r.getLong(1),r.getLong(2)});return result;}
            }
        });
        for(var item:due)attempt(item[0],item[1],"view",null);
    }

    private static List<Long> ids(Connection c,String sql,long id)throws SQLException {
        try(var s=c.prepareStatement(sql)){bind(s,id);try(var r=s.executeQuery()){List<Long> values=new ArrayList<>();while(r.next())values.add(r.getLong(1));return values;}}
    }
    private static List<Question> questions(String json){return List.of(JSON.fromJson(json,Question[].class));}
    private static Map<String,Object> metadata(ResultSet r)throws SQLException {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("id",r.getLong("id"));data.put("title",r.getString("title"));data.put("courseName",r.getString("course_name"));data.put("semester",r.getInt("semester"));
        data.put("opensAt",r.getLong("opens_at"));data.put("closesAt",r.getLong("closes_at"));data.put("durationMinutes",r.getInt("duration_minutes"));return data;
    }
    private static int score(List<Integer> answers,List<Question> paper){int score=0;for(int i=0;i<paper.size();i++)if(i<answers.size()&&Objects.equals(answers.get(i),paper.get(i).correctIndex()))score++;return score;}
    private static void finish(Connection c,long exam,long user,List<Integer> answers,List<Question> paper,long when)throws SQLException {
        update(c,"UPDATE online_exam_attempts SET submitted_at=?,score=? WHERE exam_id=? AND user_id=? AND submitted_at IS NULL",when,score(answers,paper),exam,user);
    }
    private static int update(Connection c,String sql,Object...values)throws SQLException {try(var s=c.prepareStatement(sql)){bind(s,values);return s.executeUpdate();}}
    private static void bind(PreparedStatement s,Object...values)throws SQLException{for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);}
    private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T transaction(Work<T> work){
        try(var c=database.getConnection()){
            c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}
            catch(SQLException|RuntimeException failure){c.rollback();throw failure;}
        }catch(SQLException failure){throw new DataAccessException("Could not update online exam data",failure);}
    }
}
