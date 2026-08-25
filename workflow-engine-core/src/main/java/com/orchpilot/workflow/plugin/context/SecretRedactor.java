package com.orchpilot.workflow.plugin.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the secret values a plugin read during one invocation and removes them from anything about to
 * be persisted or logged.
 *
 * <p>This is a safety net, not a licence. A plugin should never put a credential in an output, a log
 * message or a request record. But a plugin that puts an {@code Authorization} header into its request
 * record is a mistake waiting to happen, and this catches it before the header reaches
 * {@code plugin_executions} where it would sit indefinitely.
 *
 * <p>Only values of a meaningful length are tracked. Redacting a two-character secret would blank out
 * unrelated text everywhere it happened to appear, which is worse than not redacting it.
 *
 * <p>One instance per node attempt, discarded with it, so no plaintext outlives the invocation. Values are
 * held in memory only, which they already are: they came from a decrypted secret.
 */
public final class SecretRedactor {

    /** Replacement written in place of a secret. */
    public static final String MASK = "***REDACTED***";

    private static final int MIN_TRACKED_LENGTH = 6;
    private static final int MAX_TRACKED_VALUES = 64;

    private final Set<String> values = ConcurrentHashMap.newKeySet();

    /**
     * @param value a secret value that has just been handed to a plugin
     */
    public void remember(String value) {
        if (value == null || value.length() < MIN_TRACKED_LENGTH || values.size() >= MAX_TRACKED_VALUES) {
            return;
        }
        values.add(value);
    }

    /** @return whether any secret has been read during this invocation */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * @param text text to sanitise
     * @return the text with every known secret replaced by {@link #MASK}
     */
    public String redact(String text) {
        if (text == null || values.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : values) {
            if (result.contains(secret)) {
                result = result.replace(secret, MASK);
            }
        }
        return result;
    }

    /**
     * @param value map, list, string or scalar
     * @return a sanitised copy; scalars other than strings are returned unchanged
     */
    @SuppressWarnings("unchecked")
    public Object redactValue(Object value) {
        if (values.isEmpty() || value == null) {
            return value;
        }
        if (value instanceof String) {
            return redact((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) value).forEach((key, item) -> copy.put(key, redactValue(item)));
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                copy.add(redactValue(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * @param map map to sanitise
     * @return a sanitised copy
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> redactMap(Map<String, Object> map) {
        if (map == null) {
            return new LinkedHashMap<>();
        }
        Object redacted = redactValue(map);
        return redacted instanceof Map ? (Map<String, Object>) redacted : new LinkedHashMap<>(map);
    }
}
