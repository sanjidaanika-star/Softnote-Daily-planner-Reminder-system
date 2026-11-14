package backend.test;

import backend.db.DBConnection;
import backend.db.UserDAO;
import backend.model.User;
import backend.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

public final class AuthServiceIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path config = args.length == 0
                ? Path.of("config", "database.test.properties")
                : Path.of(args[0]);
        DBConnection database = new DBConnection(config);
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT DATABASE() = ?")) {
            statement.setString(1, "softnote_test");
            try (ResultSet rows = statement.executeQuery()) {
                check(rows.next() && rows.getBoolean(1), "Tests require the softnote_test database");
            }
        }

        UserDAO users = new UserDAO(database);
        AuthService auth = new AuthService(users);
        String prefix = UUID.randomUUID().toString();
        String email = prefix + "@example.test";
        String secondEmail = prefix + "+second@example.test";
        String password = "test-password-123";
        try {
            long id = auth.register("Study Tester", email, password);
            auth.register("Second Tester", secondEmail, password);
            UserDAO.Credentials first = users.findByEmail(email).orElseThrow();
            UserDAO.Credentials second = users.findByEmail(secondEmail).orElseThrow();
            check(first.user().getId() == id, "Registration must return the stored ID");
            check(first.passwordSalt().length == 32 && second.passwordSalt().length == 32,
                    "Expected 32-byte salts");
            check(!Arrays.equals(first.passwordSalt(), second.passwordSalt()), "Salts must differ");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(first.passwordSalt());
            check(Arrays.equals(first.passwordHash(), sha256.digest(password.getBytes(StandardCharsets.UTF_8))),
                    "Expected SHA-256 of the stored salt followed by the password");
            check(!Arrays.equals(first.passwordHash(), second.passwordHash()), "Salted hashes must differ");
            System.out.println("PASS register stores SHA-256 hashes and distinct salts");

            User loggedIn = auth.login(email, password);
            check(loggedIn.getId() == id && email.equals(loggedIn.getEmail())
                    && "Study Tester".equals(loggedIn.getName()), "Login must return the registered user");
            System.out.println("PASS login returns the registered user");

            String wrongPassword = loginError(auth, email, "incorrect");
            String unknownEmail = loginError(auth, prefix + "+missing@example.test", password);
            check("Invalid email or password.".equals(wrongPassword) && wrongPassword.equals(unknownEmail),
                    "Login failures must use the same generic message");
            System.out.println("PASS wrong password and unknown email return the same error");
        } finally {
            deleteTestUsers(database, email, secondEmail);
        }
    }

    private static String loginError(AuthService auth, String email, String password) {
        try {
            auth.login(email, password);
        } catch (IllegalArgumentException exception) {
            return exception.getMessage();
        }
        throw new AssertionError("Expected login to fail");
    }

    private static void deleteTestUsers(DBConnection database, String email, String secondEmail) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE email IN (?, ?)")) {
            statement.setString(1, email);
            statement.setString(2, secondEmail);
            statement.executeUpdate();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
