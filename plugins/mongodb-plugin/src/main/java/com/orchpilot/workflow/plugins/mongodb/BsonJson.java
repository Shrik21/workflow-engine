package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns configured JSON into BSON, with workflow variables resolved on the way.
 *
 * <h2>Substitution happens in the tree, not in the text</h2>
 *
 * A filter arrives from the designer already parsed — the JSON editor stores a structure, not a string — so
 * this walks that structure and resolves {@code ${...}} inside each string value. Doing it the other way, by
 * substituting into JSON text and parsing afterwards, means any value containing a quote or a backslash
 * produces a syntax error at execution: a customer named {@code O"Brien} would break a query that worked in
 * testing. Text is still accepted, for configurations written by hand or imported as JSON, and is escaped
 * accordingly.
 *
 * <h2>Types survive</h2>
 *
 * {@code {"age": {"$gte": 18}}} keeps 18 an integer, because nothing here touches a value that is not a
 * string. A placeholder that is the entire value — {@code "${form.age}"} — is coerced when the resolved text
 * is unambiguously a number or a boolean, because {@code age: "30"} matches nothing in a collection where age
 * is numeric, and that failure is silent: the query is valid, runs, and returns no documents.
 *
 * <h2>Anything less obvious is written explicitly</h2>
 *
 * A 24-character hex string stays a string, because a value that <em>looks</em> like an ObjectId frequently is
 * not one, and guessing would turn an ordinary identifier into a type the collection does not hold. MongoDB's
 * own Extended JSON says it instead, and is supported here:
 *
 * <pre>
 *   {"_id":       {"$oid": "${customer.id}"}}
 *   {"createdAt": {"$date": "${system.currentTime}"}}
 *   {"total":     {"$numberDecimal": "${order.total}"}}
 * </pre>
 *
 * That is a notation MongoDB users already know, which is why it is used rather than a convention invented
 * here.
 */
final class BsonJson {

    /** A whole value that is one placeholder and nothing else: the only case where a type is inferred. */
    private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\$\\{[^}]+}$");

    /** A JSON number, which is narrower than what Java would parse: no leading zeros, no hex, no underscores. */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

    private BsonJson() {
    }

    /**
     * Reads one document — a filter, an update, a projection.
     *
     * @param raw      the configured value: a map, or JSON text
     * @param field    the configuration field, named in any error so the operator knows which box to fix
     * @param resolve  the engine's variable resolver
     * @return the document, empty when nothing was configured
     * @throws PluginConfigurationException when the value is not a JSON object
     */
    static Document document(Object raw, String field, UnaryOperator<String> resolve) {
        if (raw == null) {
            return new Document();
        }
        Object interpolated = interpolate(raw, resolve);

        if (interpolated instanceof Map<?, ?> map) {
            return toDocument(map, field);
        }
        if (interpolated instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return new Document();
            }
            try {
                return Document.parse(trimmed);
            } catch (RuntimeException ex) {
                throw new PluginConfigurationException(
                        "'" + field + "' is not valid JSON: " + ex.getMessage());
            }
        }
        throw new PluginConfigurationException(
                "'" + field + "' must be a JSON object, not " + describe(interpolated) + ".");
    }

    /**
     * Reads a list of documents — an aggregation pipeline, a batch of documents to insert.
     *
     * @param raw     the configured value: a list, JSON text, or a single document
     * @param field   the configuration field, named in any error
     * @param resolve the engine's variable resolver
     * @return the documents, empty when nothing was configured
     */
    static List<Document> documents(Object raw, String field, UnaryOperator<String> resolve) {
        if (raw == null) {
            return List.of();
        }
        Object interpolated = interpolate(raw, resolve);

        if (interpolated instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return List.of();
            }
            // A bare array is not a document, so Document.parse cannot read one; wrapping is the documented
            // way to use the driver's own JSON reader for it.
            if (trimmed.startsWith("[")) {
                try {
                    Document wrapper = Document.parse("{\"items\": " + trimmed + "}");
                    interpolated = wrapper.get("items");
                } catch (RuntimeException ex) {
                    throw new PluginConfigurationException(
                            "'" + field + "' is not a valid JSON array: " + ex.getMessage());
                }
            } else {
                return List.of(document(trimmed, field, value -> value));
            }
        }

        if (interpolated instanceof Map<?, ?> map) {
            // One document where a list was expected is a common and harmless shape.
            return List.of(toDocument(map, field));
        }
        if (interpolated instanceof Collection<?> collection) {
            List<Document> documents = new ArrayList<>(collection.size());
            int index = 0;
            for (Object entry : collection) {
                if (!(entry instanceof Map<?, ?> map)) {
                    throw new PluginConfigurationException("'" + field + "' entry " + index
                            + " must be a JSON object, not " + describe(entry) + ".");
                }
                documents.add(toDocument(map, field + "[" + index + "]"));
                index++;
            }
            return documents;
        }
        throw new PluginConfigurationException(
                "'" + field + "' must be a JSON array, not " + describe(interpolated) + ".");
    }

    /**
     * Resolves every {@code ${...}} inside a value, at any depth.
     *
     * @param value   a map, a list, a string, or a scalar
     * @param resolve the engine's variable resolver
     * @return the same shape with variables resolved
     */
    static Object interpolate(Object value, UnaryOperator<String> resolve) {
        if (value instanceof String text) {
            return resolveString(text, resolve);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // Keys are resolved too: a field name can legitimately come from a variable, as in a
                // projection built for whichever column an operator picked.
                String key = resolve.apply(String.valueOf(entry.getKey()));
                resolved.put(key, interpolate(entry.getValue(), resolve));
            }
            return resolved;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> resolved = new ArrayList<>(collection.size());
            for (Object entry : collection) {
                resolved.add(interpolate(entry, resolve));
            }
            return resolved;
        }
        return value;
    }

    /**
     * Resolves one string, inferring a type only when the whole value was a placeholder.
     *
     * <p>{@code "${form.age}"} may become the number 30. {@code "age is ${form.age}"} stays the string
     * "age is 30", because the operator wrote text around it and clearly meant text.
     */
    private static Object resolveString(String text, UnaryOperator<String> resolve) {
        String resolved = resolve.apply(text);
        if (resolved == null) {
            return null;
        }
        if (!WHOLE_PLACEHOLDER.matcher(text.trim()).matches()) {
            return resolved;
        }

        String trimmed = resolved.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (JSON_NUMBER.matcher(trimmed).matches()) {
            return number(trimmed);
        }
        // Including anything that looks like an ObjectId: see the class comment.
        return resolved;
    }

    /**
     * Integers stay integral, so a count compares equal to a stored int rather than to a double.
     *
     * <p>Written with an {@code if} rather than a conditional expression on purpose: {@code cond ? (int) value
     * : value} has type {@code long}, so the narrowed branch is widened straight back and every value boxes as
     * a {@code Long}. It compiles, it looks right, and it silently produces a BSON int64 where an int32 was
     * intended.
     */
    private static Object number(String text) {
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
            try {
                long value = Long.parseLong(text);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return Integer.valueOf((int) value);
                }
                return Long.valueOf(value);
            } catch (NumberFormatException ex) {
                // Longer than a long: fall through to a double, which is what JSON would have produced.
            }
        }
        return Double.parseDouble(text);
    }

    /** Converts an interpolated map into a BSON document, honouring Extended JSON type wrappers. */
    private static Document toDocument(Map<?, ?> map, String field) {
        Document document = new Document();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            document.put(String.valueOf(entry.getKey()), toBson(entry.getValue(), field));
        }
        return document;
    }

    /**
     * Converts one interpolated value into what the driver should send.
     *
     * <p>The Extended JSON wrappers are handled here rather than by round-tripping through
     * {@code Document.parse}, so a malformed one names the field it is in instead of failing somewhere inside
     * a JSON reader with no idea which part of the configuration produced it.
     */
    private static Object toBson(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            String wrapper = singleDollarKey(map);
            if (wrapper != null) {
                Object inner = map.values().iterator().next();
                return typed(wrapper, inner, field);
            }
            return toDocument(map, field);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>(collection.size());
            for (Object entry : collection) {
                values.add(toBson(entry, field));
            }
            return values;
        }
        return value;
    }

    /** @return the key when the map is exactly one Extended JSON wrapper, otherwise null */
    private static String singleDollarKey(Map<?, ?> map) {
        if (map.size() != 1) {
            return null;
        }
        String key = String.valueOf(map.keySet().iterator().next());
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "$oid", "$date", "$numberint", "$numberlong", "$numberdouble", "$numberdecimal" -> key;
            default -> null;
        };
    }

    private static Object typed(String wrapper, Object inner, String field) {
        String text = String.valueOf(inner).trim();
        try {
            return switch (wrapper.toLowerCase(Locale.ROOT)) {
                case "$oid" -> new ObjectId(text);
                case "$date" -> date(text);
                case "$numberint" -> Integer.parseInt(text);
                case "$numberlong" -> Long.parseLong(text);
                case "$numberdouble" -> Double.parseDouble(text);
                case "$numberdecimal" -> Decimal128.parse(text);
                default -> inner;
            };
        } catch (IllegalArgumentException ex) {
            throw new PluginConfigurationException("In '" + field + "', " + wrapper + " was given '"
                    + text + "', which is not a valid value for it. "
                    + (wrapper.equalsIgnoreCase("$oid")
                            ? "An ObjectId is 24 hexadecimal characters."
                            : ex.getMessage()));
        }
    }

    /** ISO-8601, or milliseconds since the epoch: both are what a workflow variable is likely to hold. */
    private static java.util.Date date(String text) {
        try {
            return java.util.Date.from(Instant.parse(text));
        } catch (DateTimeParseException ex) {
            try {
                return new java.util.Date(Long.parseLong(text));
            } catch (NumberFormatException nested) {
                throw new IllegalArgumentException(
                        "expected an ISO-8601 timestamp such as 2026-08-17T09:30:00Z, or milliseconds "
                                + "since the epoch");
            }
        }
    }

    /** A name for a value's type, for a message an operator has to act on. */
    private static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof Collection<?>) {
            return "a JSON array";
        }
        if (value instanceof String) {
            return "text";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return "the value " + value;
        }
        return value.getClass().getSimpleName();
    }

    /**
     * Substitutes into JSON text, escaping what it inserts.
     *
     * <p>Used only for configurations held as text rather than as a structure. The escaping is the whole
     * reason this is not a plain string replace: a resolved value containing a quote would otherwise close the
     * JSON string it was inserted into and produce a parse error at execution time, on data-dependent input.
     *
     * @param json    JSON text possibly containing placeholders
     * @param resolve the engine's variable resolver
     * @return the text with every placeholder resolved and escaped
     */
    static String interpolateText(String json, UnaryOperator<String> resolve) {
        Matcher matcher = Pattern.compile("\\$\\{[^}]+}").matcher(json);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String resolved = String.valueOf(resolve.apply(matcher.group()));
            matcher.appendReplacement(result, Matcher.quoteReplacement(escape(resolved)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
