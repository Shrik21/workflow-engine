package com.orchpilot.workflow.utility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dotted-path navigation over nested {@code Map} and {@code List} structures.
 *
 * <p>This is the single implementation behind {@code ${workflow.order.items[0].sku}} in variable
 * templates, node configuration resolution and output mapping, so path semantics cannot drift
 * between those three features.
 *
 * <p>Grammar: segments separated by {@code .}, each optionally followed by one or more
 * {@code [index]} subscripts. Wrapping a segment in single quotes escapes a key that itself contains
 * dots, as in {@code workflow.'order.id'}.
 */
public final class MapPaths {

    private MapPaths() {
    }

    /**
     * Reads a value.
     *
     * @param root starting map, may be {@code null}
     * @param path dotted path; {@code null} or blank yields empty
     * @return the value, or empty when any segment is missing or the value is {@code null}
     */
    public static Optional<Object> find(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        Object current = root;
        for (String segment : split(path)) {
            current = step(current, segment);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(current);
    }

    /**
     * Writes a value, creating intermediate maps as needed.
     *
     * <p>List subscripts are supported for reading but not for writing: a path containing
     * {@code [n]} is rejected, because silently growing lists hides mapping mistakes.
     *
     * @param root  target map, must not be {@code null}
     * @param path  dotted path
     * @param value value to store, may be {@code null}
     * @throws IllegalArgumentException when the path is blank, contains a subscript, or traverses a
     *                                  segment already occupied by a non-map value
     */
    @SuppressWarnings("unchecked")
    public static void put(Map<String, Object> root, String path, Object value) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        List<String> segments = split(path);
        Map<String, Object> cursor = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            requireNoSubscript(segment, path);
            Object next = cursor.get(segment);
            if (next == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                cursor.put(segment, created);
                cursor = created;
            } else if (next instanceof Map) {
                cursor = (Map<String, Object>) next;
            } else {
                throw new IllegalArgumentException(
                        "Cannot write '" + path + "': segment '" + segment + "' holds a non-map value");
            }
        }
        String last = segments.get(segments.size() - 1);
        requireNoSubscript(last, path);
        cursor.put(last, value);
    }

    /**
     * @param root  target map
     * @param path  dotted path
     * @return {@code true} when a value was removed
     */
    @SuppressWarnings("unchecked")
    public static boolean remove(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return false;
        }
        List<String> segments = split(path);
        Map<String, Object> cursor = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            Object next = cursor.get(segments.get(i));
            if (!(next instanceof Map)) {
                return false;
            }
            cursor = (Map<String, Object>) next;
        }
        return cursor.remove(segments.get(segments.size() - 1)) != null;
    }

    /**
     * Deep copy of a map structure, so that a caller cannot mutate persisted state through a
     * returned snapshot. Scalars are shared, which is safe because every scalar the engine stores is
     * immutable.
     *
     * @param source map to copy, may be {@code null}
     * @return a mutable deep copy, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map) {
            return deepCopy((Map<String, Object>) value);
        }
        if (value instanceof List) {
            List<Object> items = new ArrayList<>(((List<Object>) value).size());
            for (Object item : (List<Object>) value) {
                items.add(copyValue(item));
            }
            return items;
        }
        return value;
    }

    /**
     * Splits a dotted path into segments, honouring {@code ['quoted.key']} escapes and keeping
     * {@code [index]} subscripts attached to their segment.
     *
     * @param path dotted path
     * @return ordered segments
     */
    public static List<String> split(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (c == '.' && !inQuote) {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (c == '[' && !inQuote && current.length() == 0 && !segments.isEmpty()) {
                // subscript directly after a closed segment, e.g. items['a'] -> reattach
                current.append(segments.remove(segments.size() - 1));
            }
            current.append(c);
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    private static void requireNoSubscript(String segment, String path) {
        if (segment.indexOf('[') >= 0) {
            throw new IllegalArgumentException("List subscripts are not writable: " + path);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object step(Object current, String segment) {
        String key = segment;
        List<Integer> indexes = null;
        int bracket = segment.indexOf('[');
        if (bracket >= 0) {
            key = segment.substring(0, bracket);
            indexes = parseIndexes(segment.substring(bracket));
            if (indexes == null) {
                return null;
            }
        }
        Object value = current;
        if (!key.isEmpty()) {
            if (!(value instanceof Map)) {
                return null;
            }
            value = ((Map<String, Object>) value).get(key);
        }
        if (indexes != null) {
            for (Integer index : indexes) {
                value = elementAt(value, index);
                if (value == null) {
                    return null;
                }
            }
        }
        return value;
    }

    private static Object elementAt(Object container, int index) {
        if (container instanceof List) {
            List<?> list = (List<?>) container;
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        if (container != null && container.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(container);
            return index >= 0 && index < length ? java.lang.reflect.Array.get(container, index) : null;
        }
        return null;
    }

    private static List<Integer> parseIndexes(String subscripts) {
        List<Integer> indexes = new ArrayList<>(2);
        int i = 0;
        while (i < subscripts.length()) {
            if (subscripts.charAt(i) != '[') {
                return null;
            }
            int close = subscripts.indexOf(']', i);
            if (close < 0) {
                return null;
            }
            try {
                indexes.add(Integer.parseInt(subscripts.substring(i + 1, close).trim()));
            } catch (NumberFormatException ex) {
                return null;
            }
            i = close + 1;
        }
        return indexes;
    }
}
