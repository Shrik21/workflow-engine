package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.sdk.node.NodeDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory index of loaded plugin versions and the node types they contribute.
 *
 * <p>Read on every plugin node execution and written whenever a plugin is loaded or unloaded, so every
 * operation must be safe without external locking.
 *
 * <p>Version resolution is explicit: a node that pins a version gets exactly that version, and a node
 * that names only a plugin gets the default version. Silently upgrading a pinned node would break the
 * guarantee that publishing a workflow freezes its behaviour.
 */
public interface PluginRegistry {

    /**
     * Registers a loaded version and indexes its node types.
     *
     * @param handle handle to register
     */
    void register(PluginHandle handle);

    /**
     * @param pluginId plugin id
     * @param version  exact version
     * @return the handle, or empty when that version is not loaded
     */
    Optional<PluginHandle> find(String pluginId, String version);

    /**
     * @param pluginId plugin id
     * @return the handle for the plugin's default version, or empty
     */
    Optional<PluginHandle> findDefault(String pluginId);

    /**
     * @param nodeType node type contributed by a plugin
     * @return the handle of the version that currently serves it, or empty
     */
    Optional<PluginHandle> findByNodeType(String nodeType);

    /**
     * Removes a version from the index. Does not destroy the plugin; see {@link PluginManager}.
     *
     * @param pluginId plugin id
     * @param version  version
     * @return the removed handle, or empty
     */
    Optional<PluginHandle> unregister(String pluginId, String version);

    /**
     * @param pluginId plugin id
     * @param version  version to serve unpinned references
     */
    void setDefaultVersion(String pluginId, String version);

    /**
     * @param pluginId plugin id
     * @return every loaded version of that plugin
     */
    Collection<PluginHandle> versionsOf(String pluginId);

    /** @return every loaded plugin version */
    Collection<PluginHandle> handles();

    /** @return every node type currently resolvable through a plugin */
    Set<String> nodeTypes();

    /** @return node definitions of every default version, for the design-time catalogue */
    List<NodeDefinition> nodeDefinitions();
}
