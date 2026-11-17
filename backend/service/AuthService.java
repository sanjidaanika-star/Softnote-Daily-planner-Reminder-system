package backend.service;

import backend.db.UserDAO;
import backend.model.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public final class AuthService {
    private static final int SALT_BYTES = 32;
    private static final String INVALID_CREDENTIALS = "Invalid email or password.";

    private final UserDAO users;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserDAO users) {
        this.users = users;
    }

    public long register(String name, String email, String password) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 255) {
            throw new IllegalArgumentException("Name must contain 1 to 255 characters");
        }
        if (email == null || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Email must have a valid address format");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return users.insert(name, email, hash(salt, password), salt);
    }

    public User login(String email, String password) {
        if (email == null || password == null) {
            throw new IllegalArgumentException(INVALID_CREDENTIALS);
        }

        UserDAO.Credentials credentials = users.findByEmail(email).orElse(null);
        byte[] salt = credentials == null ? new byte[SALT_BYTES] : credentials.passwordSalt();
        byte[] expected = credentials == null ? new byte[32] : credentials.passwordHash();
        boolean matches = MessageDigest.isEqual(expected, hash(salt, password));
        if (!matches || credentials == null || !users.access(credentials.user().getId()).active()) {
            throw new IllegalArgumentException(INVALID_CREDENTIALS);
        }
        return credentials.user();
    }

    public static byte[] hash(byte[] salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
