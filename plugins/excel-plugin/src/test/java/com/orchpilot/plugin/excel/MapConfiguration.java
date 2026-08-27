package com.orchpilot.plugin.excel;

import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link NodeConfiguration} over a plain map, for tests.
 *
 * <p>The engine normally hands a plugin a fully variable-resolved configuration; this stands in for that, so
 * the operations can be driven without an engine.
 */
final class MapConfiguration implements NodeConfiguration {

    private final Map<String, Object> values;

    MapConfiguration(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Object> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Object current = values;
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            current = ((Map<String, Object>) map).get(part);
        }
        return Optional.ofNullable(current);
    }

    @Override
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
