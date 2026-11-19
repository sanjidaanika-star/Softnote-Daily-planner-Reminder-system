package backend.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Loads local or deployment configuration without including the API key in diagnostics. */
public final class GeminiConfig {
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;

    private GeminiConfig(String apiKey, String model, long timeoutSeconds) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    public static GeminiConfig load(Path file) throws GenerationException {
        return load(file, System.getenv());
    }

    public static GeminiConfig load(Path file, Map<String, String> environment) throws GenerationException {
        Properties values = new Properties();
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) { values.load(reader); }
            catch (IOException | IllegalArgumentException failure) {
                throw new GenerationException("Could not read Gemini server configuration.");
            }
        }
        String key = value(values, environment, "gemini.apiKey", "SOFTNOTE_GEMINI_API_KEY", "");
        String model = value(values, environment, "gemini.model", "SOFTNOTE_GEMINI_MODEL", "");
        String timeout = value(values, environment, "gemini.timeoutSeconds", "SOFTNOTE_GEMINI_TIMEOUT_SECONDS", "45");
        if (key.isEmpty() || !key.matches("[A-Za-z0-9_-]+")) {
            throw new GenerationException("Gemini API key problem. Check the server configuration.");
        }
        if (!model.matches("[a-zA-Z0-9_-]+")) {
            throw new GenerationException("Set a valid Gemini model in the server configuration.");
        }
        long seconds;
        try { seconds = Long.parseLong(timeout); }
        catch (NumberFormatException failure) { throw timeoutProblem(); }
        if (seconds < 1 || seconds > 55) throw timeoutProblem();
        return new GeminiConfig(key, model, seconds);
    }

    private static String value(Properties values, Map<String, String> environment, String property, String variable, String fallback) {
        return environment.getOrDefault(variable, values.getProperty(property, fallback)).strip();
    }

    private static GenerationException timeoutProblem() {
        return new GenerationException("Set Gemini timeout to 1–55 seconds in the server configuration.");
    }

    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public long timeoutSeconds() { return timeoutSeconds; }
    @Override public String toString() { return "GeminiConfig[model=" + model + ", timeoutSeconds=" + timeoutSeconds + ", apiKey=REDACTED]"; }
}
