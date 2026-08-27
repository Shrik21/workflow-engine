package com.orchpilot.workflow.ai.tool;

import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.plugin.PluginNodeExecutor;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns one installed plugin node type into an {@link AITool}.
 *
 * <h2>A tool call is a plugin node execution</h2>
 *
 * The adapter does not reimplement anything a plugin does. To execute the tool it builds a synthetic
 * {@link WorkflowNode} — the plugin id, version and node type, with the model's arguments as its configuration —
 * and hands it to {@link PluginNodeExecutor}, the same single bridge every plugin node runs through. So a tool
 * call inherits, for free, the platform's leasing, idempotency, class-loader isolation, secret redaction and
 * execution recording, and runs with the exact identity and permissions of the workflow that invoked the agent.
 * The tool's schema is the plugin's own configuration schema, so the model is offered precisely the inputs the
 * plugin already declares — and never a credential.
 */
public class PluginAIToolAdapter implements AITool {

    private final String pluginId;
    private final String pluginVersion;
    private final String nodeType;
    private final NodeDefinition definition;
    private final PluginNodeExecutor pluginNodeExecutor;

    public PluginAIToolAdapter(String pluginId, String pluginVersion, NodeDefinition definition,
                               PluginNodeExecutor pluginNodeExecutor) {
        this.pluginId = pluginId;
        this.pluginVersion = pluginVersion;
        this.nodeType = definition.nodeType();
        this.definition = definition;
        this.pluginNodeExecutor = pluginNodeExecutor;
    }

    @Override
    public String getName() {
        return toolName(nodeType);
    }

    @Override
    public String getDescription() {
        String description = definition.description();
        return description == null || description.isBlank() ? definition.displayName() : description;
    }

    @Override
    public ToolSchema getSchema() {
        return new ToolSchema(getName(), getDescription(), definition.configurationSchema());
    }

    @Override
    public boolean isDestructive() {
        return definition.destructive();
    }

    @Override
    public ToolResult execute(ToolExecutionContext context) {
        WorkflowNode node = new WorkflowNode();
        node.setId("tool-" + UUID.randomUUID());
        node.setType(nodeType);
        node.setPluginId(pluginId);
        node.setPluginVersion(pluginVersion);
        node.setConfiguration(new java.util.LinkedHashMap<>(context.arguments()));

        NodeExecutionResult result = pluginNodeExecutor.execute(node, context.workflowContext());
        if (result == null) {
            return ToolResult.failure("The tool returned no result.");
        }
        if (result.isFailed()) {
            return ToolResult.failure(result.errorCode() + ": " + result.errorMessage());
        }
        return ToolResult.success(result.outputs());
    }

    /** A model-friendly name: the node type lower-cased, non-alphanumerics to underscores. */
    private static String toolName(String nodeType) {
        return nodeType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }
}
