package com.orchpilot.workflow.event;

import java.time.Instant;
import java.util.List;

/**
 * Published when a plugin version is installed, loaded, activated, deactivated, unloaded or deleted.
 *
 * <p>The node types are included so that a listener maintaining a design-time cache, such as a front
 * end's node palette, can invalidate precisely what changed instead of reloading the whole catalogue.
 *
 * @param pluginId  plugin id
 * @param version   plugin version
 * @param action    what happened, e.g. {@code LOADED}
 * @param nodeTypes node types the version contributes
 * @param at        event time
 */
public record PluginLifecycleEvent(String pluginId, String version, String action, List<String> nodeTypes,
                                   Instant at) {

    /**
     * @param pluginId  plugin id
     * @param version   plugin version
     * @param action    what happened
     * @param nodeTypes contributed node types, may be {@code null}
     * @return an event stamped now
     */
    public static PluginLifecycleEvent of(String pluginId, String version, String action,
                                          List<String> nodeTypes) {
        return new PluginLifecycleEvent(pluginId, version, action,
                nodeTypes == null ? List.of() : List.copyOf(nodeTypes), Instant.now());
    }
}
