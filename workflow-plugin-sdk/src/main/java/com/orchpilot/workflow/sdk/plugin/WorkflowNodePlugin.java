package com.orchpilot.workflow.sdk.plugin;

import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;

import java.util.List;

/**
 * A plugin that contributes one or more executable node types to the workflow designer.
 *
 * <p>This is the interface almost every integration implements. A minimal example:
 *
 * <pre>{@code
 * public class SlackPlugin implements WorkflowNodePlugin {
 *
 *     private PluginContext ctx;
 *
 *     @Override public String getId() { return "slack"; }
 *     @Override public String getName() { return "Slack Plugin"; }
 *     @Override public String getVersion() { return "1.0.0"; }
 *     @Override public String getDescription() { return "Post messages to Slack"; }
 *     @Override public PluginType getPluginType() { return PluginType.NODE; }
 *
 *     @Override public void initialize(PluginContext context) { this.ctx = context; }
 *     @Override public void destroy() { }
 *
 *     @Override public List<NodeDefinition> getNodeDefinitions() {
 *         return List.of(NodeDefinition.builder("SLACK_MESSAGE")
 *                 .displayName("Send Slack Message")
 *                 .category("Communication")
 *                 .configurationSchema(SchemaBuilder.object()
 *                         .string("channel", "Channel", true)
 *                         .text("text", "Message", true)
 *                         .build())
 *                 .outputVariables("ts")
 *                 .build());
 *     }
 *
 *     @Override public NodeExecutionResult execute(NodeExecutionContext c) {
 *         String channel = c.configuration().requireString("channel");
 *         // ... call Slack via c.pluginContext().http() ...
 *         return NodeExecutionResult.success(Map.of("ts", "1700000000.1"));
 *     }
 * }
 * }</pre>
 *
 * @since 1.0.0
 */
public interface WorkflowNodePlugin extends WorkflowPlugin {

    /**
     * Node types this plugin contributes. Called after {@code initialize} and cached by the engine
     * for the lifetime of the loaded version, so the returned list must not change afterwards.
     *
     * <p>Node types are global. Prefix them with the plugin id to avoid collisions, e.g.
     * {@code SENDGRID_EMAIL} rather than {@code EMAIL}.
     *
     * @return one or more node definitions; must not be empty for {@link PluginType#NODE}
     */
    List<NodeDefinition> getNodeDefinitions();

    /**
     * Executes one node.
     *
     * <p>Contract:
     * <ul>
     *   <li>Return {@link NodeExecutionResult#failure(String, String)} for expected failures rather
     *       than throwing; the engine then applies the node's retry and error policy. Uncaught
     *       exceptions are converted to a failure result but lose the chance to mark themselves
     *       retryable.</li>
     *   <li>Must be thread-safe: many executions call this concurrently on the same instance.</li>
     *   <li>Must not retain the {@link NodeExecutionContext} after returning.</li>
     *   <li>Should poll {@link NodeExecutionContext#isCancelled()} during long operations.</li>
     *   <li>For non-idempotent side effects, pass
     *       {@link NodeExecutionContext#idempotencyKey()} to the downstream provider, or declare
     *       {@code idempotent(false)} on the node definition and let the engine's guard replay
     *       stored outputs on retries.</li>
     * </ul>
     *
     * @param context identifiers, resolved configuration, variables and plugin services
     * @return the outcome; never {@code null}
     */
    NodeExecutionResult execute(NodeExecutionContext context);
}
