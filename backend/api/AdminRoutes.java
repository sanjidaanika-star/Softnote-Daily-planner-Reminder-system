package backend.api;

import backend.db.AdminDAO;
import backend.service.AuthService;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

final class AdminRoutes {
    private static final Gson JSON = new Gson();
    private final AdminDAO accounts;
    private final SecureRandom random = new SecureRandom();

    AdminRoutes(AdminDAO accounts) { this.accounts = accounts; }

    void handle(HttpExchange exchange, long actor) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Object result;
        if ("GET".equals(method)) {
            result = switch (path) {
                case "/admin/summary" -> accounts.summary().get(0);
                case "/admin/users" -> accounts.users();
                case "/admin/resets" -> accounts.resets();
                case "/admin/audit" -> accounts.audit();
                default -> throw new ApiServer.HttpError(404, "Admin endpoint not found");
            };
        } else if ("PATCH".equals(method) && path.matches("/admin/users/[1-9][0-9]*/active")) {
            Map<?, ?> body = ApiServer.requestBody(exchange);
            if (!(body.get("active") instanceof Boolean active)) throw new IllegalArgumentException("active must be true or false");
            accounts.setActive(actor, Long.parseLong(path.split("/")[3]), active);
            result = Map.of("message", "Account updated");
        } else if ("POST".equals(method) && path.matches("/admin/resets/[1-9][0-9]*/issue")) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            accounts.issueReset(actor, Long.parseLong(path.split("/")[3]), tokenHash(token));
            result = Map.of("resetPath", "/reset-password.html#" + token, "expiresInMinutes", 30);
        } else {
            throw new ApiServer.HttpError(404, "Admin endpoint not found");
        }
        ApiServer.reply(exchange, 200, JSON.toJson(result));
    }

    void requestReset(HttpExchange exchange) throws IOException {
        Map<?, ?> body = ApiServer.requestBody(exchange);
        Object email = body.get("email");
        if (!(email instanceof String value) || value.length() > 254) throw new IllegalArgumentException("Enter a valid email");
        accounts.requestReset(value.strip());
        ApiServer.reply(exchange, 202, JSON.toJson(Map.of("message",
                "If this account exists, a reset request has been sent to the administrator. Contact your administrator to verify your identity and receive a reset link.")));
    }

    void resetPassword(HttpExchange exchange) throws IOException {
        Map<?, ?> body = ApiServer.requestBody(exchange);
        if (!(body.get("token") instanceof String token) || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("This reset link is invalid or has expired");
        }
        if (!(body.get("password") instanceof String password) || password.length() < 8 || password.length() > 128 || password.isBlank()) {
            throw new IllegalArgumentException("Use a password with 8 to 128 characters");
        }
        byte[] salt = new byte[32];
        random.nextBytes(salt);
        accounts.resetPassword(tokenHash(token), AuthService.hash(salt, password), salt);
        ApiServer.reply(exchange, 200, "{\"message\":\"Password updated. Sign in with your new password.\"}");
    }

    private static byte[] tokenHash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
