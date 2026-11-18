package backend.reminder;

import backend.db.ReminderDAO.Delivery;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

public final class GmailSender implements ReminderSender {
    private final ReminderConfig config;
    public GmailSender(ReminderConfig config) { this.config = config; }
    @Override public void send(Delivery delivery) throws DeliveryFailure {
        if (!config.emailReady()) throw new DeliveryFailure("Email sender is not configured yet.");
        Properties options = new Properties();
        options.setProperty("mail.smtp.host", config.value("smtp.host", "smtp.gmail.com"));
        options.setProperty("mail.smtp.port", config.value("smtp.port", "587"));
        options.setProperty("mail.smtp.auth", "true");
        options.setProperty("mail.smtp.starttls.enable", "true");
        options.setProperty("mail.smtp.starttls.required", "true");
        options.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        options.setProperty("mail.smtp.connectiontimeout", "10000");
        options.setProperty("mail.smtp.timeout", "10000");
        options.setProperty("mail.smtp.writetimeout", "10000");
        Session session = Session.getInstance(options);
        session.setDebug(false);
        try (Transport transport = session.getTransport("smtp")) {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.value("smtp.from", ""), true));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(delivery.email(), true));
            message.setSubject(delivery.title(), "UTF-8");
            message.setText(delivery.message() + "\n\nOpen your plan: " + config.appUrl() + delivery.path()
                    + "\n\nManage reminders: " + config.appUrl() + "/reminders.html", "UTF-8");
            message.setSentDate(new java.util.Date());
            // A stable Message-ID helps diagnose retries. SMTP acceptance cannot guarantee inbox placement.
            message.saveChanges();
            message.setHeader("Message-ID", "<softnote-reminder-" + delivery.id() + "@softnote.local>");
            transport.connect(config.value("smtp.user", ""), config.value("smtp.password", ""));
            transport.sendMessage(message, message.getAllRecipients());
        } catch (jakarta.mail.AuthenticationFailedException error) {
            throw new DeliveryFailure("Gmail authentication failed. Ask the administrator to check the App Password.");
        } catch (Exception error) {
            // SMTP responses can contain addresses or credentials. Never expose the provider exception.
            throw new DeliveryFailure("Email delivery failed. It will be retried, up to three attempts.");
        }
    }
}
