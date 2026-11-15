package backend.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private final String input;
    private int offset;

    private Json(String input) {
        this.input = input;
    }

    static Map<?, ?> object(String input) {
        Json parser = new Json(input);
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (!(value instanceof Map<?, ?> object) || parser.offset != input.length()) {
            throw invalid();
        }
        return object;
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private Object value(int depth) {
        skipWhitespace();
        if (depth > 8 || offset >= input.length()) {
            throw invalid();
        }
        return switch (input.charAt(offset)) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readBoolean("true", true);
            case 'f' -> readBoolean("false", false);
            default -> readInteger();
        };
    }

    private boolean readBoolean(String literal, boolean value) {
        if (!input.startsWith(literal, offset)) throw invalid();
        offset += literal.length();
        return value;
    }

    private Map<String, Object> readObject(int depth) {
        offset++;
        Map<String, Object> values = new LinkedHashMap<>();
        if (!take('}')) {
            do {
                skipWhitespace();
                String key = readString();
                require(':');
                if (values.putIfAbsent(key, value(depth + 1)) != null) {
                    throw invalid();
                }
            } while (take(','));
            require('}');
        }
        return values;
    }

    private List<Object> readArray(int depth) {
        offset++;
        List<Object> values = new ArrayList<>();
        if (!take(']')) {
            do {
                values.add(value(depth + 1));
            } while (take(','));
            require(']');
        }
        return values;
    }

    private String readString() {
        if (offset >= input.length() || input.charAt(offset++) != '"') {
            throw invalid();
        }
        StringBuilder value = new StringBuilder();
        while (offset < input.length()) {
            char character = input.charAt(offset++);
            if (character == '"') {
                return value.toString();
            }
            if (character < 0x20) {
                throw invalid();
            }
            value.append(character == '\\' ? readEscape() : character);
        }
        throw invalid();
    }

    private char readEscape() {
        if (offset == input.length()) {
            throw invalid();
        }
        return switch (input.charAt(offset++)) {
            case '"', '\\', '/' -> input.charAt(offset - 1);
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicode();
            default -> throw invalid();
        };
    }

    private char readUnicode() {
        if (offset + 4 > input.length()) {
            throw invalid();
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char digit = input.charAt(offset++);
            int number = Character.digit(digit, 16);
            if (digit > 127 || number < 0) {
                throw invalid();
            }
            value = value * 16 + number;
        }
        return (char) value;
    }

    private long readInteger() {
        int start = offset;
        if (input.charAt(offset) == '-') {
            offset++;
        }
        if (offset < input.length() && input.charAt(offset) == '0') {
            offset++;
        } else {
            while (offset < input.length() && input.charAt(offset) >= '0' && input.charAt(offset) <= '9') {
                offset++;
            }
        }
        try {
            return Long.parseLong(input.substring(start, offset));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private boolean take(char character) {
        skipWhitespace();
        if (offset < input.length() && input.charAt(offset) == character) {
            offset++;
            return true;
        }
        return false;
    }

    private void require(char character) {
        if (!take(character)) {
            throw invalid();
        }
    }

    private void skipWhitespace() {
        while (offset < input.length() && " \r\n\t".indexOf(input.charAt(offset)) >= 0) {
            offset++;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Expected a JSON object containing strings, integers, objects, or arrays");
    }
}
