package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.sdk.node.NodeDefinition;

import java.util.List;
import java.util.Map;

/**
 * One entry in the node catalogue served by {@code GET /api/nodes}.
 *
 * <p>This is the contract that stops a front end needing a release every time a plugin is added. Built-in and
 * plugin-contributed node types come back in the same shape, each carrying its own configuration schema, so a
 * designer can render a palette entry and a property panel for a node type that did not exist when the front
 * end was built.
 *
 * @param nodeType            globally unique node type
 * @param displayName         palette label
 * @param category            palette grouping
 * @param icon                icon hint
 * @param description         what the node does
 * @param source              {@code BUILT_IN} or {@code PLUGIN}
 * @param pluginId            plugin providing it, when {@code source} is {@code PLUGIN}
 * @param pluginVersion       version providing it
 * @param configurationSchema JSON-schema-shaped configuration description
 * @param outputPorts         named outgoing branches
 * @param outputVariables     output names the node publishes
 * @param idempotent          whether repeating it is side-effect free
 * @param supportsRetry       whether retrying it is meaningful
 */
public record NodeCatalogEntry(String nodeType, String displayName, String category, String icon,
                               String description, String source, String pluginId, String pluginVersion,
                               Map<String, Object> configurationSchema, List<String> outputPorts,
                               List<String> outputVariables, boolean idempotent, boolean supportsRetry) {

    /** Marks a node type the engine implements itself. */
    public static final String SOURCE_BUILT_IN = "BUILT_IN";

    /** Marks a node type contributed by a runtime plugin. */
    public static final String SOURCE_PLUGIN = "PLUGIN";

    /**
     * @param definition node definition published by the engine
     * @return a built-in catalogue entry
     */
    public static NodeCatalogEntry builtIn(NodeDefinition definition) {
        return of(definition, SOURCE_BUILT_IN, null, null);
    }

    /**
     * @param definition    node definition published by a plugin
     * @param pluginId      providing plugin
     * @param pluginVersion providing version
     * @return a plugin catalogue entry
     */
    public static NodeCatalogEntry plugin(NodeDefinition definition, String pluginId, String pluginVersion) {
        return of(definition, SOURCE_PLUGIN, pluginId, pluginVersion);
    }

    private static NodeCatalogEntry of(NodeDefinition definition, String source, String pluginId,
                                       String pluginVersion) {
        return new NodeCatalogEntry(definition.nodeType(), definition.displayName(), definition.category(),
                definition.icon(), definition.description(), source, pluginId, pluginVersion,
                definition.configurationSchema(), definition.outputPorts(), definition.outputVariables(),
                definition.idempotent(), definition.supportsRetry());
    }
}
