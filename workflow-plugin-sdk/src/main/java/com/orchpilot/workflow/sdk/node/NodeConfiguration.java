package com.orchpilot.workflow.sdk.node;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed, read-only view over a node's configuration with all {@code ${...}} placeholders already
 * resolved against the execution's variables.
 *
 * <p>A plugin never sees raw templates and never has to know how variable resolution works, which
 * is what lets the engine change its expression implementation without breaking plugins.
 *
 * <p>Implementations only need to provide {@link #find(String)} and {@link #asMap()}; every typed
 * accessor is derived from those.
 *
 * @since 1.0.0
 */
public interface NodeConfiguration {

    /**
     * Looks up a configuration value by key. Dotted keys walk into nested maps, so
     * {@code find("body.customer.name")} works.
     *
     * @param key configuration key, optionally dotted
     * @return the value, or empty when absent or {@code null}
     */
    Optional<Object> find(String key);

    /**
     * @return unmodifiable snapshot of the whole resolved configuration tree
     */
    Map<String, Object> asMap();

    /**
     * @param key configuration key
     * @return {@code true} when a non-null value is present
     */
    default boolean has(String key) {
        return find(key).isPresent();
    }

    /**
     * @param key configuration key
     * @return the value rendered as text, or empty when absent
     */
    default Optional<String> findString(String key) {
        return find(key).map(String::valueOf);
    }

    /**
     * @param key          configuration key
     * @param defaultValue value returned when the key is absent
     * @return the value rendered as text, or {@code defaultValue}
     */
    default String getString(String key, String defaultValue) {
        return findString(key).orElse(defaultValue);
    }

    /**
     * @param key configuration key
     * @return the value rendered as text
     * @throws PluginConfigurationException when absent or blank
     */
    default String requireString(String key) {
        String value = findString(key).orElse(null);
        if (value == null || value.isBlank()) {
            throw new PluginConfigurationException("Required configuration '" + key + "' is missing or blank");
        }
        return value;
    }

    /**
     * @param key          configuration key
     * @param defaultValue value returned when the key is absent or unparseable
     * @return the value as an {@code int}
     */
    default int getInt(String key, int defaultValue) {
        return (int) getLong(key, defaultValue);
    }

    /**
     * @param key          configuration key
     * @param defaultValue value returned when the key is absent or unparseable
     * @return the value as a {@code long}
     */
    default long getLong(String key, long defaultValue) {
        Object raw = find(key).orElse(null);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * @param key          configuration key
     * @param defaultValue value returned when the key is absent
     * @return the value as a {@code boolean}; only {@code "true"} (case-insensitive) is true
     */
    default boolean getBoolean(String key, boolean defaultValue) {
        Object raw = find(key).orElse(null);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    /**
     * @param key configuration key
     * @return an unmodifiable nested map, or an empty map when absent or not a map
     */
    @SuppressWarnings("unchecked")
    default Map<String, Object> getMap(String key) {
        Object raw = find(key).orElse(null);
        if (raw instanceof Map) {
            return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) raw));
        }
        return Collections.emptyMap();
    }

    /**
     * Convenience for header-style configuration where every value is textual.
     *
     * @param key configuration key
     * @return an unmodifiable map with every value rendered as text
     */
    default Map<String, String> getStringMap(String key) {
        Map<String, Object> source = getMap(key);
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * @param key configuration key
     * @return an unmodifiable list, or an empty list when absent or not a list
     */
    default List<Object> getList(String key) {
        Object raw = find(key).orElse(null);
        if (raw instanceof List) {
            return List.copyOf((List<?>) raw);
        }
        return List.of();
    }
}
