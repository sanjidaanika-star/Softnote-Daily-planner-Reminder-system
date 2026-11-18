package backend.reminder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Server secrets are never included in API responses. Environment overrides the ignored file. */
public final class ReminderConfig {
    private final Properties values;
    public ReminderConfig(Properties values) { this.values = values; }
    public static ReminderConfig load() {
        Properties values = new Properties();
        Path path = Path.of("config", "reminders.properties");
        if (Files.exists(path)) {
            try (var input = Files.newInputStream(path)) { values.load(input); }
            catch (Exception error) { throw new IllegalStateException("Could not read reminder configuration"); }
        }
        return new ReminderConfig(values);
    }
    public String value(String name, String fallback) {
        String env = System.getenv("SOFTNOTE_" + name.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
        return env == null ? values.getProperty(name, fallback).strip() : env.strip();
    }
    public boolean emailReady() {
        if ("brevo".equals(value("mail.provider", "brevo"))) {
            return !value("brevo.apiKey", "").isBlank() && !value("mail.from", "").isBlank();
        }
        if (!"smtp".equals(value("mail.provider", "brevo"))) return false;
        return !value("smtp.user", "").isBlank() && !value("smtp.password", "").isBlank()
                && !value("smtp.from", "").isBlank();
    }
    public boolean pushReady() {
        return !value("push.publicKey", "").isBlank() && !value("push.privateKey", "").isBlank()
                && !value("push.subject", "").isBlank();
    }
    public String appUrl() {
        String url = value("app.url", "http://127.0.0.1:8080");
        java.net.URI uri = java.net.URI.create(url);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())))) {
            throw new IllegalArgumentException("Configure a valid app.url for reminder links");
        }
        return url.replaceAll("/+$", "");
    }
}
