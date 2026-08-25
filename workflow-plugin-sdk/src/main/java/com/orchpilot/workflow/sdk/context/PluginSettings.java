package com.orchpilot.workflow.sdk.context;

import java.util.Map;
import java.util.Optional;

/**
 * Installation-scoped, non-secret configuration for one plugin version, supplied by an
 * administrator at upload or activation time.
 *
 * <p>Distinct from node configuration, which is per-node and per-execution, and from
 * {@link SecretProvider}, which is the only place credentials come from.
 *
 * @since 1.0.0
 */
public interface PluginSettings {

    /**
     * @param key setting name
     * @return the raw value, or empty when absent
     */
    Optional<Object> find(String key);

    /**
     * @return unmodifiable snapshot of all settings
     */
    Map<String, Object> asMap();

    /**
     * @param key          setting name
     * @param defaultValue value returned when absent
     * @return the setting rendered as text, or {@code defaultValue}
     */
    default String getString(String key, String defaultValue) {
        return find(key).map(String::valueOf).orElse(defaultValue);
    }

    /**
     * @param key          setting name
     * @param defaultValue value returned when absent or unparseable
     * @return the setting as an {@code int}
     */
    default int getInt(String key, int defaultValue) {
        Object raw = find(key).orElse(null);
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * @param key          setting name
     * @param defaultValue value returned when absent
     * @return the setting as a {@code boolean}
     */
    default boolean getBoolean(String key, boolean defaultValue) {
        Object raw = find(key).orElse(null);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        return raw == null ? defaultValue : Boolean.parseBoolean(String.valueOf(raw).trim());
    }
}
