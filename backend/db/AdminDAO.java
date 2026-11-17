package backend.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AdminDAO {
    private final DBConnection database;

    public AdminDAO(DBConnection database) { this.database = database; }

    public List<Map<String, Object>> users() {
        return query("""
                SELECT u.id, u.name, u.email, u.role, u.active, u.created_at,
                  (SELECT COUNT(*) FROM exams e WHERE e.user_id = u.id) AS exams,
                  (SELECT COUNT(*) FROM notes n WHERE n.user_id = u.id) AS notes
                FROM users u ORDER BY u.created_at DESC, u.id DESC LIMIT 1000
                """);
    }

    public List<Map<String, Object>> summary() {
        return query("""
                SELECT (SELECT COUNT(*) FROM users) AS users,
                  (SELECT COUNT(*) FROM users WHERE active = TRUE) AS active,
                  (SELECT COUNT(*) FROM exams) AS exams, (SELECT COUNT(*) FROM notes) AS notes,
                  (SELECT COUNT(*) FROM quizzes) AS quizzes,
                  (SELECT COUNT(*) FROM quizzes WHERE status = 'failed') AS failedQuizzes,
                  (SELECT COUNT(*) FROM password_reset_requests WHERE status = 'pending') AS pendingResets
                """);
    }

    public List<Map<String, Object>> resets() {
        return query("""
                SELECT r.id, r.user_id, u.name, u.email, r.status, r.requested_at, r.expires_at
                FROM password_reset_requests r JOIN users u ON u.id = r.user_id
                ORDER BY r.id DESC LIMIT 200
                """);
    }

    public List<Map<String, Object>> audit() {
        return query("""
                SELECT a.id, actor.email AS actor, target.email AS target, a.action, a.created_at
                FROM admin_audit a JOIN users actor ON actor.id = a.actor_id
                JOIN users target ON target.id = a.target_id ORDER BY a.id DESC LIMIT 100
                """);
    }

    private List<Map<String, Object>> query(String sql) {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            List<Map<String, Object>> items = new ArrayList<>();
            while (rows.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    Object value = rows.getObject(column);
                    item.put(rows.getMetaData().getColumnLabel(column),
                            value instanceof java.sql.Timestamp ? value.toString() : value);
                }
                items.add(item);
            }
            return items;
        } catch (SQLException exception) {
            throw new DataAccessException("Could not load administration data", exception);
        }
    }

    public void setActive(long actor, long target, boolean active) {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET active = ?, auth_version = auth_version + 1 WHERE id = ? AND role = 'user'")) {
                statement.setBoolean(1, active);
                statement.setLong(2, target);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Choose an existing user account");
            }
            audit(connection, actor, target, active ? "Account enabled" : "Account disabled");
        });
    }

    public void requestReset(String email) {
        transaction(connection -> {
            long user;
            try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM users WHERE email = ? FOR UPDATE")) {
                lock.setString(1, email);
                try (ResultSet rows = lock.executeQuery()) {
                    if (!rows.next()) return;
                    user = rows.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO password_reset_requests (user_id)
                    SELECT ? WHERE NOT EXISTS (SELECT 1 FROM password_reset_requests
                      WHERE user_id = ? AND status IN ('pending','issued') AND requested_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR)
                    """)) {
                statement.setLong(1, user);
                statement.setLong(2, user);
                statement.executeUpdate();
            }
        });
    }

    public void issueReset(long actor, long request, byte[] tokenHash) {
        transaction(connection -> {
            long user;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_id FROM password_reset_requests WHERE id = ? AND status IN ('pending','issued') FOR UPDATE")) {
                statement.setLong(1, request);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Reset request is no longer available");
                    user = rows.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE password_reset_requests SET status = 'issued', token_hash = ?,
                    expires_at = CURRENT_TIMESTAMP + INTERVAL 30 MINUTE WHERE id = ?
                    """)) {
                statement.setBytes(1, tokenHash);
                statement.setLong(2, request);
                statement.executeUpdate();
            }
            audit(connection, actor, user, "Password reset link issued");
        });
    }

    public void resetPassword(byte[] tokenHash, byte[] hash, byte[] salt) {
        transaction(connection -> {
            long request, user;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, user_id FROM password_reset_requests
                    WHERE token_hash = ? AND status = 'issued' AND expires_at > CURRENT_TIMESTAMP FOR UPDATE
                    """)) {
                statement.setBytes(1, tokenHash);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("This reset link is invalid or has expired");
                    request = rows.getLong(1);
                    user = rows.getLong(2);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET password_hash = ?, password_salt = ?, auth_version = auth_version + 1 WHERE id = ?")) {
                statement.setBytes(1, hash);
                statement.setBytes(2, salt);
                statement.setLong(3, user);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE password_reset_requests SET status = 'used', token_hash = NULL WHERE id = ?")) {
                statement.setLong(1, request);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE password_reset_requests SET status = 'cancelled', token_hash = NULL
                    WHERE user_id = ? AND status IN ('pending','issued')
                    """)) {
                statement.setLong(1, user);
                statement.executeUpdate();
            }
        });
    }

    private static void audit(Connection connection, long actor, long target, String action) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO admin_audit (actor_id, target_id, action) VALUES (?, ?, ?)")) {
            statement.setLong(1, actor);
            statement.setLong(2, target);
            statement.setString(3, action);
            statement.executeUpdate();
        }
    }

    private void transaction(Operation operation) {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                operation.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not update account administration", exception);
        }
    }

    @FunctionalInterface
    private interface Operation { void run(Connection connection) throws SQLException; }
}
