package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A minimal {@link NodeConfiguration} over a plain map, standing in for the engine-resolved config in tests. */
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
