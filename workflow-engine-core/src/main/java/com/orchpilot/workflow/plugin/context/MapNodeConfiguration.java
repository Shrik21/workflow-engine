package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.utility.MapPaths;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A node's configuration, already resolved, exposed to a plugin as read-only.
 *
 * <p>The map is unmodifiable and the plugin only ever sees resolved values, so a plugin cannot mutate what
 * the engine will record as the node's configuration, and cannot see the raw {@code ${...}} templates.
 * Dotted lookups are delegated to the engine's single path implementation so that
 * {@code configuration().find("body.customer.name")} behaves exactly like the same path in a template.
 */
public class MapNodeConfiguration implements NodeConfiguration {

    private final Map<String, Object> resolved;

    /**
     * @param resolved fully resolved configuration, may be {@code null}
     */
    public MapNodeConfiguration(Map<String, Object> resolved) {
        this.resolved = resolved == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
    }

    @Override
    public Optional<Object> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Object direct = resolved.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        return MapPaths.find(resolved, key);
    }

    @Override
    public Map<String, Object> asMap() {
        return resolved;
    }
}
