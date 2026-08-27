package com.orchpilot.workflow.support.testplugin;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A real plugin, used by tests that need one.
 *
 * <p>Packaged into a JAR at test time by {@code TestJars} and loaded through the engine's own class loader and plugin
 * manager, so the tests exercise the actual loading path rather than a stub. It touches no network and no secrets, so
 * it can run with the most restrictive permissions.
 */
public class EchoPlugin implements WorkflowNodePlugin {

    /** Node type this test plugin contributes. */
    public static final String NODE_TYPE = "ECHO";

    private PluginContext context;
    private boolean initialised;
    private boolean destroyed;

    @Override
    public String getId() {
        return "echo";
    }

    @Override
    public String getName() {
        return "Echo Test Plugin";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "Returns its configuration as node outputs";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) {
        this.context = pluginContext;
        this.initialised = true;
        pluginContext.logger().info("Echo plugin initialised");
    }

    @Override
    public void destroy() {
        this.destroyed = true;
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder(NODE_TYPE)
                .displayName("Echo")
                .category("Testing")
                .description("Returns the message it was given")
                .configurationSchema(SchemaBuilder.object()
                        .string("message", "Message", true)
                        .build())
                .outputVariables("message", "echoedAt", "attempt")
                .idempotent(true)
                .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        String message = executionContext.configuration().requireString("message");
        if ("fail".equalsIgnoreCase(message)) {
            return NodeExecutionResult.failure("ECHO_ASKED_TO_FAIL", "The message was 'fail'");
        }
        if ("throw".equalsIgnoreCase(message)) {
            throw new IllegalStateException("The message was 'throw'");
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("message", message);
        outputs.put("echoedAt", java.time.Instant.now().toString());
        outputs.put("attempt", executionContext.attempt());
        outputs.put("idempotencyKey", executionContext.idempotencyKey());
        return NodeExecutionResult.success(outputs);
    }

    /** @return whether {@code initialize} has run, for assertions about lifecycle */
    public boolean isInitialised() {
        return initialised;
    }

    /** @return whether {@code destroy} has run, for assertions about lifecycle */
    public boolean isDestroyed() {
        return destroyed;
    }

    /** @return the context handed to this plugin, or {@code null} before initialisation */
    public PluginContext context() {
        return context;
    }
}
