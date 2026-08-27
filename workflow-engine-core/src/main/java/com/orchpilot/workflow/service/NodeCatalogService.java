package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.NodeCatalogEntry;
import com.orchpilot.workflow.node.BuiltInNodeCatalog;
import com.orchpilot.workflow.plugin.PluginHandle;
import com.orchpilot.workflow.plugin.PluginRegistry;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles the node catalogue served by {@code GET /api/nodes}.
 *
 * <p>This endpoint is what removes the front end from the plugin release cycle. It returns built-in and
 * plugin-contributed node types in one uniform shape, each carrying the configuration schema its provider
 * published, so a designer can render a palette entry and a property panel for a node type that was uploaded
 * minutes ago.
 *
 * <p>The catalogue is computed on demand rather than cached. Plugins load and unload at runtime, and a stale palette
 * that offers a node type the engine can no longer execute is worse than the cost of walking a handful of
 * in-memory registry entries.
 */
@Service
public class NodeCatalogService {

    private static final Logger log = LoggerFactory.getLogger(NodeCatalogService.class);

    private final BuiltInNodeCatalog builtInCatalog;
    private final PluginRegistry pluginRegistry;
    private final com.orchpilot.workflow.repository.PluginVersionRepository versionRepository;

    public NodeCatalogService(BuiltInNodeCatalog builtInCatalog, PluginRegistry pluginRegistry,
                              com.orchpilot.workflow.repository.PluginVersionRepository versionRepository) {
        this.builtInCatalog = builtInCatalog;
        this.pluginRegistry = pluginRegistry;
        this.versionRepository = versionRepository;
    }

    /**
     * @param category optional category filter, matched case-insensitively
     * @return every node type this instance can currently execute
     */
    public List<NodeCatalogEntry> catalogue(String category) {
        List<NodeCatalogEntry> entries = new ArrayList<>();
        for (NodeDefinition definition : builtInCatalog.definitions()) {
            entries.add(NodeCatalogEntry.builtIn(definition));
        }
        for (String nodeType : new LinkedHashSet<>(pluginRegistry.nodeTypes())) {
            Optional<PluginHandle> handle = pluginRegistry.findByNodeType(nodeType);
            if (handle.isEmpty()) {
                continue;
            }
            handle.get().nodeDefinition(nodeType).ifPresent(definition ->
                    entries.add(NodeCatalogEntry.plugin(definition, handle.get().pluginId(),
                            handle.get().version())));
        }
        if (category != null && !category.isBlank()) {
            String wanted = category.trim();
            entries.removeIf(entry -> !wanted.equalsIgnoreCase(entry.category()));
        }
        entries.sort((left, right) -> {
            int byCategory = String.valueOf(left.category()).compareToIgnoreCase(String.valueOf(right.category()));
            return byCategory != 0 ? byCategory : left.nodeType().compareTo(right.nodeType());
        });
        log.debug("Node catalogue served with {} entry/entries", entries.size());
        return entries;
    }

    /**
     * @param nodeType node type to describe
     * @return the catalogue entry, or empty when nothing provides it
     */
    public Optional<NodeCatalogEntry> describe(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return Optional.empty();
        }
        for (NodeDefinition definition : builtInCatalog.definitions()) {
            if (nodeType.equals(definition.nodeType())) {
                return Optional.of(NodeCatalogEntry.builtIn(definition));
            }
        }
        return pluginRegistry.findByNodeType(nodeType)
                .flatMap(handle -> handle.nodeDefinition(nodeType)
                        .map(definition -> NodeCatalogEntry.plugin(definition, handle.pluginId(),
                                handle.version())));
    }

    /**
     * @return categories present in the catalogue, in catalogue order, for building a palette's groups
     */
    public Set<String> categories() {
        Set<String> categories = new LinkedHashSet<>();
        for (NodeCatalogEntry entry : catalogue(null)) {
            categories.add(entry.category());
        }
        return categories;
    }

    /**
     * The icons currently loaded plugins ship, keyed by plugin id.
     *
     * <p>Read from the loaded set rather than from every installed version, so the icon a designer sees always
     * belongs to the code that would actually run. An inactive or unloaded plugin contributes no node types
     * either, so its artwork has nothing to decorate.
     *
     * @return plugin id to {@code data:} URL, omitting plugins that ship no icon
     */
    public java.util.Map<String, String> pluginIcons() {
        java.util.Map<String, String> icons = new java.util.LinkedHashMap<>();
        for (String nodeType : new LinkedHashSet<>(pluginRegistry.nodeTypes())) {
            pluginRegistry.findByNodeType(nodeType).ifPresent(handle -> {
                if (icons.containsKey(handle.pluginId())) {
                    return;
                }
                versionRepository.findById(
                                com.orchpilot.workflow.model.PluginVersion.idFor(handle.pluginId(),
                                        handle.version()))
                        .map(com.orchpilot.workflow.model.PluginVersion::getIconDataUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .ifPresent(url -> icons.put(handle.pluginId(), url));
            });
        }
        return icons;
    }
}
