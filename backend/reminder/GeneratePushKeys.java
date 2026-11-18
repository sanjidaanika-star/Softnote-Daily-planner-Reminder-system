package backend.reminder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Properties;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Writes secrets to an ignored config file instead of printing them into terminal logs. */
public final class GeneratePushKeys {
    public static void main(String[] args) throws Exception {
        Path file = Path.of("config", "reminders.properties");
        Properties p = new Properties();
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { p.load(in); }
        if (!p.getProperty("push.privateKey", "").isBlank()) throw new IllegalStateException("Push keys already exist; keep them stable.");
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
        generator.initialize(new ECGenParameterSpec("prime256v1"));
        var keys = generator.generateKeyPair();
        var base64 = Base64.getUrlEncoder().withoutPadding();
        p.setProperty("push.publicKey", base64.encodeToString(Utils.encode((ECPublicKey) keys.getPublic())));
        p.setProperty("push.privateKey", base64.encodeToString(Utils.encode((ECPrivateKey) keys.getPrivate())));
        try (var out = Files.newOutputStream(file)) { p.store(out, "Keep private. Do not commit."); }
        System.out.println("Push key pair saved to config/reminders.properties. Set push.subject and app.url before starting.");
    }
}
