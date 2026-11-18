package backend.reminder;

import backend.db.ReminderDAO.Delivery;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.security.Security;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class WebPushSender implements ReminderSender {
    private final ReminderConfig config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    static { if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider()); }
    public WebPushSender(ReminderConfig config) { this.config = config; }

    public static void validateSubscription(String endpoint, String publicKey, String auth) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            // Only known browser push services. Never permit arbitrary server-side HTTP requests.
            boolean allowed = "fcm.googleapis.com".equals(host) || "updates.push.services.mozilla.com".equals(host)
                    || "web.push.apple.com".equals(host) || (host != null && host.endsWith(".notify.windows.com"));
            if (!allowed || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getFragment() != null || endpoint.length() > 2048) throw new Exception();
            byte[] key = Base64.getUrlDecoder().decode(publicKey), secret = Base64.getUrlDecoder().decode(auth);
            if (key.length != 65 || key[0] != 4 || secret.length != 16) throw new Exception();
            nl.martijndwars.webpush.Utils.loadPublicKey(publicKey);
        } catch (Exception error) { throw new IllegalArgumentException("Invalid browser push subscription"); }
    }

    public HttpRequest prepare(Delivery delivery) throws Exception {
            validateSubscription(delivery.endpoint(), delivery.publicKey(), delivery.authSecret());
            String payload = new Gson().toJson(Map.of("title", delivery.title(), "body", delivery.message(),
                    "url", delivery.path(), "tag", "softnote-" + delivery.reminderId()));
            PushService push = new PushService(config.value("push.publicKey", ""),
                    config.value("push.privateKey", ""), config.value("push.subject", ""));
            var post = push.preparePost(new Notification(delivery.endpoint(), delivery.publicKey(),
                    delivery.authSecret(), payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), 3600), Encoding.AES128GCM);
            byte[] encrypted;
            try (var input = post.getEntity().getContent()) { encrypted = input.readAllBytes(); }
            var request = HttpRequest.newBuilder(URI.create(delivery.endpoint())).timeout(Duration.ofSeconds(15));
            for (var header : post.getAllHeaders()) {
                if (!"Content-Length".equalsIgnoreCase(header.getName())) request.header(header.getName(), header.getValue());
            }
            return request.POST(HttpRequest.BodyPublishers.ofByteArray(encrypted)).build();
    }

    @Override public void send(Delivery delivery) throws DeliveryFailure {
        if (!config.pushReady()) throw new DeliveryFailure("Browser push is not configured yet.");
        try {
            int code = client.send(prepare(delivery),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 404 || code == 410) throw new DeliveryFailure("Browser subscription expired. Enable notifications again.", true);
            if (code < 200 || code >= 300) throw new DeliveryFailure("Browser push was not accepted. It will be retried.");
        } catch (DeliveryFailure error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new DeliveryFailure("Browser push interrupted."); }
        catch (Exception error) { throw new DeliveryFailure("Browser push failed. Check server configuration or try again later."); }
    }
}
