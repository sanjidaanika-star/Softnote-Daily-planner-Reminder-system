package backend.db;

import backend.model.User;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

public final class UserDAO {
    private final DBConnection database;

    public UserDAO(DBConnection database) {
        this.database = database;
    }

    public long insert(String name, String email, byte[] passwordHash, byte[] passwordSalt) {
        String sql = "INSERT INTO users (name, email, password_hash, password_salt) VALUES (?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, email);
            statement.setBytes(3, passwordHash);
            statement.setBytes(4, passwordSalt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Insert returned no user ID");
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not create user", exception);
        }
    }

    public Optional<Credentials> findByEmail(String email) {
        String sql = """
                SELECT id, name, email, created_at, password_hash, password_salt
                FROM users WHERE email = ?
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                User user = new User(rows.getLong("id"), rows.getString("name"),
                        rows.getString("email"), rows.getObject("created_at", LocalDateTime.class));
                return Optional.of(new Credentials(user, rows.getBytes("password_hash"),
                        rows.getBytes("password_salt")));
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Could not look up user credentials", exception);
        }
    }

    public record Credentials(User user, byte[] passwordHash, byte[] passwordSalt) {}
}
