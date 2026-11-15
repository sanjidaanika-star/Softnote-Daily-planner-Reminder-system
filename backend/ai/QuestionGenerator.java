package backend.ai;

@FunctionalInterface
public interface QuestionGenerator {
    String generate(String text, int count) throws GenerationException;
}
