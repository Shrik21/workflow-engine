package com.orchpilot.workflow.ai.tool;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.plugin.PluginNodeExecutor;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plugin→tool adapter: it exposes a plugin as a tool and runs a tool call as a plugin node execution,
 * reusing the engine's single plugin bridge rather than reimplementing anything.
 */
class PluginAIToolAdapterTest {

    private static NodeDefinition restApiDefinition() {
        return NodeDefinition.builder("REST_API_CALL")
                .displayName("REST API Call")
                .category("HTTP")
                .description("Calls an HTTP endpoint.")
                .configurationSchema(Map.of("type", "object", "properties",
                        Map.of("url", Map.of("type", "string"))))
                .supportsAI(true)
                .build();
    }

    @Test
    @DisplayName("the tool's name, description and schema come from the plugin's node definition")
    void schemaFromPlugin() {
        PluginNodeExecutor executor = mock(PluginNodeExecutor.class);
        PluginAIToolAdapter tool = new PluginAIToolAdapter("restapi", "1.0.1", restApiDefinition(), executor);

        assertThat(tool.getName()).isEqualTo("rest_api_call");
        assertThat(tool.getDescription()).isEqualTo("Calls an HTTP endpoint.");
        assertThat(tool.getSchema().parameters()).containsKey("properties");
    }

    @Test
    @DisplayName("executing the tool runs the plugin node with the arguments as its configuration")
    void executeRunsPluginNode() {
        PluginNodeExecutor executor = mock(PluginNodeExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(NodeExecutionResult.success(Map.of("status", 200, "body", "ok")));
        PluginAIToolAdapter tool = new PluginAIToolAdapter("restapi", "1.0.1", restApiDefinition(), executor);

        WorkflowExecutionContext context = mock(WorkflowExecutionContext.class);
        ToolResult result = tool.execute(new ToolExecutionContext(context,
                Map.of("url", "https://example.test")));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("status", 200);

        // The plugin ran through the standard executor, as a node carrying the plugin id and the tool arguments.
        ArgumentCaptor<WorkflowNode> node = ArgumentCaptor.forClass(WorkflowNode.class);
        verify(executor).execute(node.capture(), any());
        assertThat(node.getValue().getType()).isEqualTo("REST_API_CALL");
        assertThat(node.getValue().getPluginId()).isEqualTo("restapi");
        assertThat(node.getValue().getConfiguration()).containsEntry("url", "https://example.test");
    }

    @Test
    @DisplayName("a plugin failure becomes a tool failure the model can read, not an exception")
    void failureBecomesToolFailure() {
        PluginNodeExecutor executor = mock(PluginNodeExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(NodeExecutionResult.failure("HTTP_ERROR", "connection refused"));
        PluginAIToolAdapter tool = new PluginAIToolAdapter("restapi", "1.0.1", restApiDefinition(), executor);

        ToolResult result = tool.execute(new ToolExecutionContext(mock(WorkflowExecutionContext.class),
                Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("HTTP_ERROR").contains("connection refused");
    }
}
