package backend.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QuizValidator {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    public record Question(String question, List<String> options, int correctIndex, String explanation) {}

    public static List<Question> parse(String text, int limit) throws GenerationException {
        try {
            JsonObject response = JSON.fromJson(text, JsonObject.class);
            if (response == null || !(response.get("questions") instanceof JsonArray items)) {
                throw new GenerationException("Gemini returned invalid quiz data.");
            }
            List<Question> questions = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement item : items) {
                Question question = read(item);
                if (question != null && seen.add(question.question())) questions.add(question);
                if (questions.size() == limit) break;
            }
            return List.copyOf(questions);
        } catch (JsonSyntaxException exception) {
            throw new GenerationException("Gemini returned invalid quiz data.");
        }
    }

    private static Question read(JsonElement item) {
        if (!(item instanceof JsonObject object)) return null;
        String question = string(object.get("question"));
        String explanation = string(object.get("explanation"));
        JsonElement correct = object.get("correctIndex");
        if (question.isEmpty() || explanation.isEmpty()
                || correct == null || !correct.isJsonPrimitive() || !correct.getAsJsonPrimitive().isNumber()
                || !correct.getAsString().matches("[0-3]")
                || !(object.get("options") instanceof JsonArray options) || options.size() != 4) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (JsonElement option : options) {
            String label = string(option);
            if (label.isEmpty()) return null;
            labels.add(label);
        }
        if (new HashSet<>(labels).size() != 4) return null;
        return new Question(question, List.copyOf(labels), correct.getAsInt(), explanation);
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().strip() : "";
    }
}
