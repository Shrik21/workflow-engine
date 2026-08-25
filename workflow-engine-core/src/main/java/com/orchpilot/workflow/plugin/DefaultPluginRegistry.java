package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.sdk.node.NodeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent plugin index.
 *
 * <p>Three maps, each with a distinct job:
 * <ul>
 *   <li>{@code byCoordinate}: exact {@code pluginId:version} lookup, used by pinned nodes.</li>
 *   <li>{@code defaultVersions}: which version serves unpinned references.</li>
 *   <li>{@code nodeTypeIndex}: node type to handle, so a node declaring {@code SENDGRID_EMAIL} with no
 *       plugin coordinate still resolves.</li>
 * </ul>
 *
 * <p>The node type index points at the default version. When a second version of the same plugin loads
 * and becomes the default, the index follows it; the older version stays reachable to nodes that pinned
 * it. Unregistering rebuilds the affected index entries rather than blindly deleting them, so removing
 * version 2.0.0 correctly hands its node types back to 1.1.0 instead of making them unresolvable.
 */
@Component
public class DefaultPluginRegistry implements PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginRegistry.class);

    private final Map<String, PluginHandle> byCoordinate = new ConcurrentHashMap<>();
    private final Map<String, String> defaultVersions = new ConcurrentHashMap<>();
    private final Map<String, PluginHandle> nodeTypeIndex = new ConcurrentHashMap<>();

    @Override
    public synchronized void register(PluginHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle must not be null");
        }
        byCoordinate.put(handle.coordinate(), handle);
        // A freshly registered version becomes the default: uploading a new version is an explicit act,
        // and an operator who wants the old one to stay default can set it back through the API. Nodes
        // that pinned a version are unaffected either way.
        defaultVersions.put(handle.pluginId(), handle.version());
        reindexNodeTypes(handle.pluginId());
        log.info("Registered plugin {} contributing node types {}", handle.coordinate(),
                handle.nodeTypes());
    }

    @Override
    public Optional<PluginHandle> find(String pluginId, String version) {
        if (pluginId == null || version == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCoordinate.get(pluginId + ":" + version));
    }

    @Override
    public Optional<PluginHandle> findDefault(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        String version = defaultVersions.get(pluginId);
        return version == null ? Optional.empty() : find(pluginId, version);
    }

    @Override
    public Optional<PluginHandle> findByNodeType(String nodeType) {
        return nodeType == null ? Optional.empty() : Optional.ofNullable(nodeTypeIndex.get(nodeType));
    }

    @Override
    public synchronized Optional<PluginHandle> unregister(String pluginId, String version) {
        if (pluginId == null || version == null) {
            return Optional.empty();
        }
        PluginHandle removed = byCoordinate.remove(pluginId + ":" + version);
        if (removed == null) {
            return Optional.empty();
        }
        if (version.equals(defaultVersions.get(pluginId))) {
            defaultVersions.remove(pluginId);
            // Promote any remaining loaded version so pinned-free workflows keep working.
            versionsOf(pluginId).stream()
                    .findFirst()
                    .ifPresent(remaining -> defaultVersions.put(pluginId, remaining.version()));
        }
        reindexNodeTypes(pluginId);
        log.info("Unregistered plugin {}", removed.coordinate());
        return Optional.of(removed);
    }

    @Override
    public synchronized void setDefaultVersion(String pluginId, String version) {
        if (find(pluginId, version).isEmpty()) {
            throw new IllegalArgumentException("Version '" + version + "' of plugin '" + pluginId
                    + "' is not loaded and cannot be made the default");
        }
        defaultVersions.put(pluginId, version);
        reindexNodeTypes(pluginId);
        log.info("Default version of plugin {} is now {}", pluginId, version);
    }

    @Override
    public Collection<PluginHandle> versionsOf(String pluginId) {
        List<PluginHandle> versions = new ArrayList<>();
        if (pluginId == null) {
            return versions;
        }
        for (PluginHandle handle : byCoordinate.values()) {
            if (pluginId.equals(handle.pluginId())) {
                versions.add(handle);
            }
        }
        return versions;
    }

    @Override
    public Collection<PluginHandle> handles() {
        return List.copyOf(byCoordinate.values());
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.copyOf(nodeTypeIndex.keySet());
    }

    @Override
    public List<NodeDefinition> nodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, PluginHandle> entry : nodeTypeIndex.entrySet()) {
            if (!seen.add(entry.getKey())) {
                continue;
            }
            entry.getValue().nodeDefinition(entry.getKey()).ifPresent(definitions::add);
        }
        return definitions;
    }

    /**
     * Rebuilds the node type index for one plugin from scratch.
     *
     * <p>Recomputing beats incremental edits here: the index must always point at the current default
     * version, and after a load, an unload or a default change, working out the delta correctly is harder
     * to get right than rebuilding a handful of entries.
     */
    private void reindexNodeTypes(String pluginId) {
        nodeTypeIndex.entrySet().removeIf(entry -> pluginId.equals(entry.getValue().pluginId()));
        findDefault(pluginId).ifPresent(handle -> {
            for (String nodeType : handle.nodeTypes()) {
                PluginHandle previous = nodeTypeIndex.put(nodeType, handle);
                if (previous != null && !previous.pluginId().equals(pluginId)) {
                    log.warn("Node type '{}' is now served by plugin {} and no longer by {}", nodeType,
                            handle.coordinate(), previous.coordinate());
                }
            }
        });
    }
}
