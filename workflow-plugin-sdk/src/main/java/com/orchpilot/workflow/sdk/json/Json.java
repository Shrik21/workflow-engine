package com.orchpilot.workflow.sdk.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader and writer for the plain {@code Map}, {@code List} and
 * scalar types the SDK already speaks.
 *
 * <p>It exists so that a plugin can build a request body or read a response without pulling in a
 * JSON library. That matters more than it looks: the SDK is delegated parent-first by the plugin
 * class loader, so any library the SDK depended on would silently become a version every plugin
 * author had to live with. Plugins that need full data binding are free to bundle their own
 * Jackson under {@code lib/} in their JAR, where the child-first class loader keeps it isolated from
 * the engine's copy.
 *
 * <h2>Type mapping</h2>
 * <table border="1">
 *   <caption>JSON to Java</caption>
 *   <tr><th>JSON</th><th>Java</th></tr>
 *   <tr><td>object</td><td>{@link LinkedHashMap}, insertion-ordered</td></tr>
 *   <tr><td>array</td><td>{@link ArrayList}</td></tr>
 *   <tr><td>string</td><td>{@link String}</td></tr>
 *   <tr><td>integral number</td><td>{@link Long}</td></tr>
 *   <tr><td>fractional number</td><td>{@link Double}</td></tr>
 *   <tr><td>true / false</td><td>{@link Boolean}</td></tr>
 *   <tr><td>null</td><td>{@code null}</td></tr>
 * </table>
 *
 * <p>On write, {@code Map}, {@code Iterable}, array, {@code Number}, {@code Boolean},
 * {@code CharSequence} and {@code null} are handled natively; anything else is written as its
 * {@code toString()} rendered as a JSON string.
 *
 * @since 1.0.0
 */
public final class Json {

    private Json() {
    }

    /**
     * @param value value to serialise
     * @return compact JSON text
     * @throws JsonException on unsupported nesting depth
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(128);
        writeValue(value, out, -1, 0);
        return out.toString();
    }

    /**
     * @param value value to serialise
     * @return indented JSON text, for logs and documentation
     * @throws JsonException on unsupported nesting depth
     */
    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(value, out, 0, 0);
        return out.toString();
    }

    /**
     * @param json JSON text
     * @return the parsed value, possibly {@code null}
     * @throws JsonException on malformed input
     */
    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("Unexpected trailing content at offset " + parser.position());
        }
        return value;
    }

    /**
     * @param json JSON text describing an object
     * @return the parsed object, or an empty map when {@code json} is {@code null} or blank
     * @throws JsonException when the text is not a JSON object
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new JsonException("Expected a JSON object but found " + describe(value));
        }
        return (Map<String, Object>) value;
    }

    /**
     * @param json JSON text describing an array
     * @return the parsed array, or an empty list when {@code json} is {@code null} or blank
     * @throws JsonException when the text is not a JSON array
     */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        Object value = parse(json);
        if (!(value instanceof List)) {
            throw new JsonException("Expected a JSON array but found " + describe(value));
        }
        return (List<Object>) value;
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- writing

    private static final int MAX_DEPTH = 64;

    private static void writeValue(Object value, StringBuilder out, int indent, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("JSON nesting deeper than " + MAX_DEPTH + " levels");
        }
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence) {
            writeString(value.toString(), out);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            writeNumber((Number) value, out);
        } else if (value instanceof Map<?, ?>) {
            writeObject((Map<?, ?>) value, out, indent, depth);
        } else if (value instanceof Iterable<?>) {
            writeArray((Iterable<?>) value, out, indent, depth);
        } else if (value.getClass().isArray()) {
            writeArray(toIterable(value), out, indent, depth);
        } else if (value instanceof Enum<?>) {
            writeString(((Enum<?>) value).name(), out);
        } else {
            writeString(value.toString(), out);
        }
    }

    private static Iterable<?> toIterable(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            items.add(java.lang.reflect.Array.get(array, i));
        }
        return items;
    }

    private static void writeNumber(Number number, StringBuilder out) {
        double asDouble = number.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            out.append("null");
            return;
        }
        out.append(number);
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int indent, int depth) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newlineIndent(out, indent, depth + 1);
            writeString(String.valueOf(entry.getKey()), out);
            out.append(':');
            if (indent >= 0) {
                out.append(' ');
            }
            writeValue(entry.getValue(), out, indent, depth + 1);
        }
        if (!first) {
            newlineIndent(out, indent, depth);
        }
        out.append('}');
    }

    private static void writeArray(Iterable<?> items, StringBuilder out, int indent, int depth) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newlineIndent(out, indent, depth + 1);
            writeValue(item, out, indent, depth + 1);
        }
        if (!first) {
            newlineIndent(out, indent, depth);
        }
        out.append(']');
    }

    private static void newlineIndent(StringBuilder out, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        out.append("  ".repeat(depth));
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- reading

    private static final class Parser {

        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source;
        }

        private int position() {
            return index;
        }

        private boolean atEnd() {
            return index >= source.length();
        }

        private void skipWhitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("JSON nesting deeper than " + MAX_DEPTH + " levels");
            }
            skipWhitespace();
            if (atEnd()) {
                throw new JsonException("Unexpected end of JSON input");
            }
            char c = source.charAt(index);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject(int depth) {
            Map<String, Object> result = new LinkedHashMap<>();
            index++; // consume '{'
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException("Expected a property name at offset " + index);
                }
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new JsonException("Expected ':' after property '" + key + "' at offset " + index);
                }
                index++;
                result.put(key, readValue(depth + 1));
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                } else if (next == '}') {
                    index++;
                    return result;
                } else {
                    throw new JsonException("Expected ',' or '}' at offset " + index);
                }
            }
        }

        private List<Object> readArray(int depth) {
            List<Object> result = new ArrayList<>();
            index++; // consume '['
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(readValue(depth + 1));
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                } else if (next == ']') {
                    index++;
                    return result;
                } else {
                    throw new JsonException("Expected ',' or ']' at offset " + index);
                }
            }
        }

        private String readString() {
            index++; // consume opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("Unterminated string literal");
                }
                char c = source.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new JsonException("Unterminated escape sequence");
                }
                char escape = source.charAt(index++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) {
                            throw new JsonException("Truncated unicode escape at offset " + index);
                        }
                        String hex = source.substring(index, index + 4);
                        index += 4;
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Invalid unicode escape '\\u" + hex + "'", ex);
                        }
                    }
                    default -> throw new JsonException("Invalid escape '\\" + escape + "' at offset " + index);
                }
            }
        }

        private Object readNumber() {
            int start = index;
            if (peek() == '-' || peek() == '+') {
                index++;
            }
            boolean fractional = false;
            while (!atEnd()) {
                char c = source.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    fractional = fractional || c == '.' || c == 'e' || c == 'E';
                    index++;
                } else {
                    break;
                }
            }
            String text = source.substring(start, index);
            if (text.isEmpty()) {
                throw new JsonException("Expected a value at offset " + start);
            }
            try {
                if (fractional) {
                    return Double.valueOf(text);
                }
                return Long.valueOf(text);
            } catch (NumberFormatException ex) {
                throw new JsonException("Invalid number '" + text + "' at offset " + start, ex);
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of JSON input");
            }
            return source.charAt(index);
        }

        private void expect(String literal) {
            if (!source.startsWith(literal, index)) {
                throw new JsonException("Expected '" + literal + "' at offset " + index);
            }
            index += literal.length();
        }
    }
}
