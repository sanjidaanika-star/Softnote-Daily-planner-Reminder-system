package backend.ai;

import backend.db.QuizDAO;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

public final class QuizService {
    private final QuizDAO quizzes;
    private final QuestionGenerator generator;
    private final Executor executor;
    private final Semaphore generationSlot = new Semaphore(1);

    public QuizService(QuizDAO quizzes, QuestionGenerator generator, Executor executor) {
        this.quizzes = quizzes;
        this.generator = generator;
        this.executor = executor;
    }

    public long start(long userId, long noteId, int count) {
        if (count < 15 || count > 20) throw new IllegalArgumentException("Choose 15 to 20 questions.");
        String text = quizzes.noteText(userId, noteId);
        if (!generationSlot.tryAcquire()) {
            throw new IllegalStateException("A quiz is already generating. Try again shortly.");
        }
        long id;
        try {
            id = quizzes.create(userId, noteId, count);
        } catch (RuntimeException exception) {
            generationSlot.release();
            throw exception;
        }
        try {
            executor.execute(() -> generate(id, text, count));
        } catch (RejectedExecutionException exception) {
            generationSlot.release();
            quizzes.fail(id, "Quiz generation is temporarily unavailable. Try again.");
        }
        return id;
    }

    private void generate(long id, String text, int count) {
        try {
            var questions = QuizValidator.parse(generator.generate(GeminiClient.capNote(text), count), count);
            if (questions.size() < 5) {
                quizzes.fail(id, "Fewer than five valid questions could be made from these notes.");
            } else {
                quizzes.ready(id, questions);
            }
        } catch (GenerationException exception) {
            quizzes.fail(id, exception.getMessage());
        } catch (RuntimeException exception) {
            quizzes.fail(id, "Quiz generation failed. Please try again.");
        } finally {
            generationSlot.release();
        }
    }
}
