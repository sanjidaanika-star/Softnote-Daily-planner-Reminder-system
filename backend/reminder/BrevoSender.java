package backend.reminder;

import backend.db.ReminderDAO.Delivery;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** HTTPS delivery works on hosting tiers where outbound SMTP is blocked. */
public final class BrevoSender implements ReminderSender {
    private final ReminderConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public BrevoSender(ReminderConfig config) { this.config = config; }
    @Override public void send(Delivery delivery) throws DeliveryFailure {
        if (!config.emailReady()) throw new DeliveryFailure("Email sender is not configured yet.");
        try {
            String content = delivery.message() + "\n\nOpen Softnote: " + config.appUrl() + delivery.path()
                    + "\n\nManage reminders: " + config.appUrl() + "/reminders.html";
            String payload = new Gson().toJson(Map.of(
                    "sender", Map.of("name", "Softnote", "email", config.value("mail.from", "")),
                    "to", List.of(Map.of("email", delivery.email())),
                    "subject", delivery.title(), "textContent", content,
                    "headers", Map.of("idempotencyKey", UUID.nameUUIDFromBytes((config.appUrl() + ":" + delivery.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())));
            var request = HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                    .timeout(Duration.ofSeconds(15)).header("api-key", config.value("brevo.apiKey", ""))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new DeliveryFailure("Email provider rejected the credentials or sender. Contact the administrator.");
            if (status == 429) throw new DeliveryFailure("Email sending limit reached. Delivery will be retried.");
            if (status < 200 || status >= 300) throw new DeliveryFailure("Email provider did not accept this reminder. Check sender verification.");
        } catch (DeliveryFailure failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new DeliveryFailure("Email delivery interrupted."); }
        catch (Exception failure) { throw new DeliveryFailure("Email delivery failed. It will be retried, up to three attempts."); }
    }
}
