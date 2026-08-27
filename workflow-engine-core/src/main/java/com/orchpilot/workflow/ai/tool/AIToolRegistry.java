package com.orchpilot.workflow.ai.tool;

import com.orchpilot.workflow.plugin.PluginHandle;
import com.orchpilot.workflow.plugin.PluginNodeExecutor;
import com.orchpilot.workflow.plugin.PluginRegistry;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Discovers the plugin-backed tools available to an AI Agent, and resolves the ones an agent is configured to
 * use into live {@link AITool}s.
 *
 * <h2>Two gates, never an automatic grant</h2>
 *
 * Discovery ({@link #available}) lists what <em>could</em> be a tool — every installed plugin node type, with the
 * plugin author's {@code supportsAI} hint. Resolution ({@link #resolve}) turns only the tools an operator
 * <em>selected</em> for a specific agent into adapters, and only for plugins that are actually installed. An
 * agent therefore never receives every plugin automatically — the specification's firm rule — and a selected
 * tool that is no longer installed simply does not resolve rather than failing mysteriously at call time. The
 * deeper authorization (does this run's user hold the plugin's permissions) is enforced where every plugin node
 * is: inside {@link PluginNodeExecutor}, which the adapter delegates to.
 */
@Component
public class AIToolRegistry {

    private final PluginRegistry pluginRegistry;
    private final PluginNodeExecutor pluginNodeExecutor;

    public AIToolRegistry(PluginRegistry pluginRegistry, PluginNodeExecutor pluginNodeExecutor) {
        this.pluginRegistry = pluginRegistry;
        this.pluginNodeExecutor = pluginNodeExecutor;
    }

    /** A tool an agent may be configured with, for the designer's tool picker. */
    public record ToolDescriptor(String pluginId, String pluginVersion, String nodeType, String toolName,
                                 String displayName, String description, String category, boolean supportsAI,
                                 boolean destructive) {
    }

    /** An agent's selection of a tool: which plugin node type it may call. */
    public record ToolSelection(String pluginId, String nodeType) {
    }

    /** Every installed plugin node type, as a candidate tool. */
    public List<ToolDescriptor> available() {
        Map<String, ToolDescriptor> byKey = new LinkedHashMap<>();
        for (PluginHandle handle : pluginRegistry.handles()) {
            for (NodeDefinition definition : handle.nodeDefinitions()) {
                String key = handle.pluginId() + "::" + definition.nodeType();
                byKey.putIfAbsent(key, new ToolDescriptor(handle.pluginId(), handle.version(),
                        definition.nodeType(), toolName(definition.nodeType()), definition.displayName(),
                        definition.description(), definition.category(), definition.supportsAI(),
                        definition.destructive()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Resolves an agent's selected tools into live adapters, skipping any whose plugin is not installed.
     *
     * @param selections the tools the agent was configured with
     * @return the resolvable tools, ready to execute
     */
    public List<AITool> resolve(List<ToolSelection> selections) {
        List<AITool> tools = new ArrayList<>();
        if (selections == null) {
            return tools;
        }
        for (ToolSelection selection : selections) {
            handleFor(selection).ifPresent(handle ->
                    handle.nodeDefinition(selection.nodeType()).ifPresent(definition ->
                            tools.add(new PluginAIToolAdapter(handle.pluginId(), handle.version(), definition,
                                    pluginNodeExecutor))));
        }
        return tools;
    }

    private java.util.Optional<PluginHandle> handleFor(ToolSelection selection) {
        if (selection.pluginId() != null && !selection.pluginId().isBlank()) {
            return pluginRegistry.findDefault(selection.pluginId());
        }
        return pluginRegistry.findByNodeType(selection.nodeType());
    }

    private static String toolName(String nodeType) {
        return nodeType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }
}
