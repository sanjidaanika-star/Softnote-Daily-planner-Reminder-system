package backend.api;

import backend.ai.GeminiClient;
import backend.ai.QuizService;
import backend.db.QuizDAO;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

final class QuizRoutes {
    private static final int MAX_PDF_BYTES = 8 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private final QuizDAO quizzes;
    private final QuizService service;

    QuizRoutes(QuizDAO quizzes, QuizService service) {
        this.quizzes = quizzes;
        this.service = service;
    }

    void notes(HttpExchange exchange, long userId) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            send(exchange, 200, quizzes.listNotes(userId));
            return;
        }
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !"application/pdf".equalsIgnoreCase(type.split(";", 2)[0].strip())) {
            throw new ApiServer.HttpError(415, "Upload a PDF file.");
        }
        byte[] pdf = exchange.getRequestBody().readNBytes(MAX_PDF_BYTES + 1);
        if (pdf.length > MAX_PDF_BYTES) throw new ApiServer.HttpError(413, "PDF must be 8 MB or smaller.");
        String title = exchange.getRequestHeaders().getFirst("X-Note-Title");
        if (title != null) title = URLDecoder.decode(title, StandardCharsets.UTF_8);
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new IllegalArgumentException("Provide a note title of 1 to 255 characters.");
        }
        String text = extractText(pdf);
        if (text.isBlank()) throw new IllegalArgumentException("No selectable text found in this PDF.");
        send(exchange, 201, new QuizDAO.Note(quizzes.saveNote(userId, title.strip(), text), title.strip()));
    }

    void start(HttpExchange exchange, long userId) throws IOException {
        String[] path = exchange.getRequestURI().getPath().split("/", -1);
        if (path.length != 4 || !"quizzes".equals(path[3])) throw notFound();
        int count = number(ApiServer.requestBody(exchange), "count", 15, 20);
        try {
            long id = service.start(userId, id(path[2]), count);
            send(exchange, 202, Map.of("id", id, "status", "generating"));
        } catch (NoSuchElementException exception) {
            throw notFound();
        } catch (IllegalStateException exception) {
            throw new ApiServer.HttpError(429, exception.getMessage());
        }
    }

    void quiz(HttpExchange exchange, long userId) throws IOException {
        String[] path = exchange.getRequestURI().getPath().split("/", -1);
        try {
            if (path.length == 3 && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, quizzes.read(userId, id(path[2])));
            } else if (path.length == 4 && "score".equals(path[3]) && "GET".equals(exchange.getRequestMethod())) {
                send(exchange, 200, quizzes.score(userId, id(path[2])));
            } else if (path.length == 6 && "questions".equals(path[3]) && "answer".equals(path[5])
                    && "POST".equals(exchange.getRequestMethod())) {
                long quizId = id(path[2]);
                long questionId = id(path[4]);
                int chosen = number(ApiServer.requestBody(exchange), "chosenIndex", 0, 3);
                send(exchange, 200, quizzes.answer(userId, quizId, questionId, chosen));
            } else {
                throw notFound();
            }
        } catch (NoSuchElementException exception) {
            throw notFound();
        } catch (IllegalStateException exception) {
            throw new ApiServer.HttpError(409, exception.getMessage());
        }
    }

    private static int number(Map<?, ?> body, String name, int min, int max) {
        Object value = body.get(name);
        if (!(value instanceof Long number) || number < min || number > max) {
            throw new IllegalArgumentException(name + " must be an integer from " + min + " to " + max + ".");
        }
        return number.intValue();
    }

    private static long id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw notFound();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw notFound();
        }
    }

    private static ApiServer.HttpError notFound() {
        return new ApiServer.HttpError(404, "Not found");
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        ApiServer.reply(exchange, status, JSON.toJson(value));
    }

    private static String extractText(byte[] pdf) {
        StringBuilder text = new StringBuilder();
        try (var document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.writeText(document, new Writer() {
                @Override
                public void write(char[] buffer, int offset, int length) throws IOException {
                    int remaining = GeminiClient.NOTE_LIMIT - text.length();
                    text.append(buffer, offset, Math.min(remaining, length));
                    if (length >= remaining) throw new TextLimitReached();
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
        } catch (TextLimitReached exception) {
            int last = text.length() - 1;
            if (Character.isHighSurrogate(text.charAt(last))) text.setLength(last);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read the PDF. Upload an unlocked, text-based PDF.");
        }
        return text.toString();
    }

    private static final class TextLimitReached extends IOException {}
}
