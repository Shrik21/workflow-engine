package com.orchpilot.workflow.plugins.vpn.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, tolerant JSON reader for the handful of fields a provider response actually carries.
 *
 * <h2>Why not a JSON library</h2>
 *
 * Jackson is not delegated to plugins parent-first, so using the engine's copy is not an option, and bundling
 * one would put a megabyte of library in the archive to read three fields out of a status response. A provider
 * here needs to pull {@code status}, {@code state} and a nested id out of a well-formed cloud API reply, not to
 * round-trip arbitrary documents — so this parses the standard grammar into maps, lists, strings, numbers and
 * booleans, and nothing more. It is deliberately small enough to read in one sitting and to trust.
 *
 * <p>Reads only; there is no writer, because every request this plugin builds is assembled by hand where the
 * exact wire form matters (a signature is computed over it).
 */
public final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses a JSON document.
     *
     * @param text the document
     * @return a Map, List, String, Number, Boolean or null
     * @throws IllegalArgumentException when the text is not valid JSON
     */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index < parser.text.length()) {
            throw new IllegalArgumentException("Trailing content after JSON value at " + parser.index);
        }
        return value;
    }

    /** Reads a document known to be an object, returning an empty map for anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * Follows a dotted path into parsed JSON, stepping into lists by numeric index.
     *
     * @param root  a parsed value
     * @param path  such as {@code VpnConnections.0.State}
     * @return the value at the path, or null
     */
    @SuppressWarnings("unchecked")
    public static Object at(Object root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(segment);
            } else if (current instanceof List<?> list) {
                try {
                    int position = Integer.parseInt(segment);
                    current = position >= 0 && position < list.size() ? list.get(position) : null;
                } catch (NumberFormatException ex) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** @return the value at a path as trimmed text, or empty */
    public static String text(Object root, String path) {
        Object value = at(root, path);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected , or } at " + index);
            }
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected , or ] at " + index);
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\') {
                char escape = next();
                switch (escape) {
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    case '/' -> value.append('/');
                    case 'n' -> value.append('\n');
                    case 't' -> value.append('\t');
                    case 'r' -> value.append('\r');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        String hex = text.substring(index, index + 4);
                        index += 4;
                        value.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("Bad escape \\" + escape + " at " + index);
                }
            } else {
                value.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = index;
        while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        String number = text.substring(start, index);
        if (number.isEmpty()) {
            throw new IllegalArgumentException("Expected a value at " + start);
        }
        if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException ex) {
                // Falls through to double for a value wider than a long.
            }
        }
        return Double.parseDouble(number);
    }

    private Boolean readBoolean() {
        if (text.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected a boolean at " + index);
    }

    private Object readNull() {
        if (text.startsWith("null", index)) {
            index += 4;
            return null;
        }
        throw new IllegalArgumentException("Expected null at " + index);
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        return text.charAt(index);
    }

    private char next() {
        return text.charAt(index++);
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw new IllegalArgumentException("Expected " + expected + " but found " + c + " at " + index);
        }
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}
