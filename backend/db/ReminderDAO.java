package backend.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

public final class ReminderDAO {
    private final DBConnection database;
    public ReminderDAO(DBConnection database) { this.database = database; }

    public record Settings(String dailyTime, String timeZone, boolean dailyEnabled, boolean examEnabled,
                           boolean emailEnabled, boolean pushEnabled) {
        public Settings {
            if (dailyTime == null || !dailyTime.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException("Choose a valid reminder time");
            try { LocalTime.parse(dailyTime); } catch (DateTimeException error) { throw new IllegalArgumentException("Choose a valid reminder time"); }
            if (timeZone == null || timeZone.length() > 80) throw new IllegalArgumentException("Choose a valid time zone");
            try { ZoneId.of(timeZone); } catch (DateTimeException error) { throw new IllegalArgumentException("Choose a valid time zone"); }
        }
        public static Settings defaults() { return new Settings("20:00", "Asia/Dhaka", false, false, false, false); }
    }
    public record Account(long id, String email, Settings settings) {}
    public record Delivery(long id, long reminderId, String channel, long targetId, long userId, String email,
                           String title, String message, String path, String endpoint, String publicKey, String authSecret) {}

    public Account account(long user) {
        String sql = """
                SELECT u.id, u.email, s.* FROM users u LEFT JOIN reminder_settings s ON s.user_id=u.id
                WHERE u.id=? AND u.active=?
                """;
        try (var c = database.getConnection(); var s = c.prepareStatement(sql)) {
            s.setLong(1, user); s.setBoolean(2, true);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Account is unavailable");
                return new Account(user, r.getString("email"), r.getObject("user_id") == null ? Settings.defaults() : readSettings(r));
            }
        } catch (SQLException error) { throw failure("Could not load reminder settings", error); }
    }
    public void save(long user, Settings value) {
        String sql = """
                INSERT INTO reminder_settings (user_id,daily_time,time_zone,daily_enabled,exam_enabled,email_enabled,push_enabled)
                VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE daily_time=?,time_zone=?,daily_enabled=?,exam_enabled=?,email_enabled=?,push_enabled=?
                """;
        try (var c = database.getConnection(); var s = c.prepareStatement(sql)) {
            s.setLong(1, user);
            for (int base : new int[]{2, 8}) {
                s.setTime(base, Time.valueOf(LocalTime.parse(value.dailyTime()))); s.setString(base+1, value.timeZone());
                s.setBoolean(base+2, value.dailyEnabled()); s.setBoolean(base+3, value.examEnabled());
                s.setBoolean(base+4, value.emailEnabled()); s.setBoolean(base+5, value.pushEnabled());
            }
            s.executeUpdate();
        } catch (SQLException error) { throw failure("Could not save reminder settings", error); }
    }
    public List<Account> enabledAccounts() {
        List<Account> accounts = new ArrayList<>();
        try (var c = database.getConnection(); var s = c.prepareStatement("""
                SELECT u.id,u.email,s.* FROM reminder_settings s JOIN users u ON u.id=s.user_id
                WHERE u.active=? AND (s.daily_enabled=? OR s.exam_enabled=?)
                """)) {
            s.setBoolean(1,true); s.setBoolean(2,true); s.setBoolean(3,true);
            try (var r=s.executeQuery()) { while(r.next()) accounts.add(new Account(r.getLong("id"),r.getString("email"),readSettings(r))); }
            return accounts;
        } catch (SQLException error) { throw failure("Could not load reminder schedule",error); }
    }
    public List<Map<String,Object>> dailySessions(long user, LocalDate date) {
        String sql = """
                SELECT e.id AS examId,e.title,c.name,s.planned_minutes AS minutes FROM study_sessions s
                JOIN exams e ON e.id=s.exam_id JOIN chapters c ON c.id=s.chapter_id
                WHERE e.user_id=? AND s.session_date=? AND s.status=? ORDER BY e.id,s.id
                """;
        return query(sql,user,date,"planned");
    }
    public List<Map<String,Object>> tomorrowExams(long user, LocalDate date) {
        return query("SELECT id,title FROM exams WHERE user_id=? AND exam_date=? ORDER BY id",user,date.plusDays(1));
    }
    public long subscribe(long user, String endpoint, String key, String auth) {
        // One browser subscription is assigned to only the most recently signed-in owner.
        String sql = """
                INSERT INTO push_subscriptions (user_id,endpoint_hash,endpoint,public_key,auth_secret) VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE user_id=?,public_key=?,auth_secret=?
                """;
        try (var c=database.getConnection()) {
            c.setAutoCommit(false);
            try (var lock=c.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                lock.setLong(1,user); try(var r=lock.executeQuery()){ if(!r.next()) throw new SQLException("Account missing"); }
                try(var count=c.prepareStatement("SELECT COUNT(*) FROM push_subscriptions WHERE user_id=? AND endpoint_hash<>?")) {
                    count.setLong(1,user); count.setString(2,hash(endpoint));
                    try(var r=count.executeQuery()){r.next(); if(r.getInt(1)>=5) throw new IllegalArgumentException("Up to five browsers can receive reminders. Disable an old browser first.");}
                }
                try(var s=c.prepareStatement(sql)){ bind(s,user,hash(endpoint),endpoint,key,auth,user,key,auth); s.executeUpdate(); }
                c.commit(); return 1;
            } catch(Exception error){c.rollback(); throw error;}
        } catch(SQLException error){throw failure("Could not enable browser reminders",error);}
    }
    public void unsubscribe(long user,String endpoint) {
        update("DELETE FROM push_subscriptions WHERE user_id=? AND endpoint_hash=?",user,hash(endpoint));
    }
    public long enqueue(Account account,String key,String title,String message,String path) {
        try(var c=database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var s=c.prepareStatement("INSERT IGNORE INTO reminders (user_id,event_key,title,message,target_path) VALUES (?,?,?,?,?)")) {
                    bind(s,account.id(),key,title,message,path);
                    if(s.executeUpdate()==0){c.rollback();return 0;}
                }
                long id;
                try(var s=c.prepareStatement("SELECT id FROM reminders WHERE user_id=? AND event_key=?")){
                    bind(s,account.id(),key);try(var r=s.executeQuery()){r.next();id=r.getLong(1);}
                }
                if(account.settings().emailEnabled()) try(var s=c.prepareStatement("INSERT INTO reminder_deliveries (reminder_id,channel,target_id) VALUES (?,?,?)")){
                    bind(s,id,"email",0);s.executeUpdate();
                }
                if(account.settings().pushEnabled()) try(var s=c.prepareStatement("INSERT INTO reminder_deliveries (reminder_id,channel,target_id) SELECT ?,?,id FROM push_subscriptions WHERE user_id=?")){
                    bind(s,id,"push",account.id());s.executeUpdate();
                }
                c.commit();return id;
            } catch(SQLException error){c.rollback();throw error;}
        } catch(SQLException error){throw failure("Could not save reminder",error);}
    }
    public List<Map<String,Object>> inbox(long user) {
        return query("""
                SELECT r.id,r.title,r.message,r.target_path AS path,UNIX_TIMESTAMP(r.created_at) AS createdAtEpoch,r.is_read AS isRead,
                (SELECT d.status FROM reminder_deliveries d WHERE d.reminder_id=r.id AND d.channel='email' LIMIT 1) AS emailStatus,
                (SELECT d.failure_reason FROM reminder_deliveries d WHERE d.reminder_id=r.id AND d.channel='email' LIMIT 1) AS emailFailure,
                (SELECT GROUP_CONCAT(DISTINCT d.status ORDER BY d.status) FROM reminder_deliveries d WHERE d.reminder_id=r.id AND d.channel='push') AS pushStatus
                FROM reminders r WHERE r.user_id=? ORDER BY r.id DESC LIMIT ?
                """,user,50);
    }
    public boolean read(long user,long id) { return update("UPDATE reminders SET is_read=? WHERE id=? AND user_id=?",true,id,user)>0; }
    public long unread(long user) { return ((Number)query("SELECT COUNT(*) AS count FROM reminders WHERE user_id=? AND is_read=?",user,false).get(0).get("count")).longValue(); }

    public List<Delivery> due() {
        update("UPDATE reminder_deliveries SET status=?,failure_reason=? WHERE status=? AND attempts>=? AND next_attempt<CURRENT_TIMESTAMP",
                "failed","Delivery could not be confirmed after the last attempt.","sending",3);
        List<Delivery> deliveries=new ArrayList<>();
        String sql="""
                SELECT d.id,d.reminder_id,d.channel,d.target_id,r.user_id,u.email,r.title,r.message,r.target_path,
                p.endpoint,p.public_key,p.auth_secret FROM reminder_deliveries d
                JOIN reminders r ON r.id=d.reminder_id JOIN users u ON u.id=r.user_id
                LEFT JOIN push_subscriptions p ON p.id=d.target_id AND p.user_id=r.user_id
                WHERE ((d.status=? AND d.next_attempt<=CURRENT_TIMESTAMP) OR (d.status=? AND d.next_attempt<CURRENT_TIMESTAMP))
                AND d.attempts<? ORDER BY d.id LIMIT ?
                """;
        try(var c=database.getConnection();var s=c.prepareStatement(sql)){
            bind(s,"pending","sending",3,20);
            try(var r=s.executeQuery()){while(r.next()) deliveries.add(new Delivery(r.getLong(1),r.getLong(2),r.getString(3),r.getLong(4),
                    r.getLong(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12)));}
            return deliveries;
        }catch(SQLException error){throw failure("Could not load pending reminders",error);}
    }
    public boolean claim(long id) {
        return update("""
                UPDATE reminder_deliveries SET status=?,attempts=attempts+1,next_attempt=CURRENT_TIMESTAMP+INTERVAL 5 MINUTE
                WHERE id=? AND attempts<? AND ((status=? AND next_attempt<=CURRENT_TIMESTAMP) OR (status=? AND next_attempt<CURRENT_TIMESTAMP))
                ""","sending",id,3,"pending","sending")>0;
    }
    public void delivered(long id){update("UPDATE reminder_deliveries SET status=?,failure_reason=NULL WHERE id=?","sent",id);}
    public void cancelled(long id){update("UPDATE reminder_deliveries SET status=? WHERE id=?","cancelled",id);}
    public void failed(Delivery delivery,String reason,boolean expired) {
        update("""
                UPDATE reminder_deliveries SET status=CASE WHEN attempts>=? THEN ? ELSE ? END,
                next_attempt=CURRENT_TIMESTAMP+INTERVAL 5 MINUTE,failure_reason=? WHERE id=?
                """,expired?1:3,"failed","pending",reason,delivery.id());
        if(expired) update("DELETE FROM push_subscriptions WHERE id=? AND user_id=?",delivery.targetId(),delivery.userId());
    }
    public boolean allowed(Delivery delivery) {
        Account account=account(delivery.userId());
        if (delivery.path().startsWith("/plan.html?examId=")) {
            long examId=Long.parseLong(delivery.path().substring("/plan.html?examId=".length()));
            if(query("SELECT id FROM exams WHERE id=? AND user_id=?",examId,delivery.userId()).isEmpty()) return false;
        }
        return "email".equals(delivery.channel()) ? account.settings().emailEnabled()
                : account.settings().pushEnabled() && delivery.endpoint()!=null;
    }
    private static Settings readSettings(ResultSet r)throws SQLException {
        return new Settings(r.getTime("daily_time").toLocalTime().toString().substring(0,5),r.getString("time_zone"),
                r.getBoolean("daily_enabled"),r.getBoolean("exam_enabled"),r.getBoolean("email_enabled"),r.getBoolean("push_enabled"));
    }
    private List<Map<String,Object>> query(String sql,Object...args) {
        try(var c=database.getConnection();var s=c.prepareStatement(sql)) {
            bind(s,args);try(var r=s.executeQuery()){
                List<Map<String,Object>> list=new ArrayList<>();var meta=r.getMetaData();
                while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),r.getObject(i));list.add(row);}return list;
            }
        } catch(SQLException error){throw failure("Could not read reminders",error);}
    }
    private int update(String sql,Object...args){try(var c=database.getConnection();var s=c.prepareStatement(sql)){bind(s,args);return s.executeUpdate();}catch(SQLException error){throw failure("Could not update reminders",error);}}
    private static void bind(PreparedStatement s,Object...args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static DataAccessException failure(String message,SQLException error){return new DataAccessException(message,error);}
}
