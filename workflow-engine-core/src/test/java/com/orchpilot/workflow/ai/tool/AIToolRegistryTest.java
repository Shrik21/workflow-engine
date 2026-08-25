package com.orchpilot.workflow.ai.tool;

import com.orchpilot.workflow.plugin.PluginHandle;
import com.orchpilot.workflow.plugin.PluginNodeExecutor;
import com.orchpilot.workflow.plugin.PluginRegistry;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tool registry: discovery lists installed plugin node types, and resolution builds adapters only for the
 * tools an agent selected and only for plugins that are still installed.
 */
class AIToolRegistryTest {

    private PluginRegistry pluginRegistry;
    private AIToolRegistry registry;

    private static NodeDefinition def() {
        return NodeDefinition.builder("REST_API_CALL").displayName("REST API Call").category("HTTP")
                .description("Calls an HTTP endpoint.").supportsAI(true).build();
    }

    @BeforeEach
    void setUp() {
        pluginRegistry = mock(PluginRegistry.class);
        PluginNodeExecutor executor = mock(PluginNodeExecutor.class);
        registry = new AIToolRegistry(pluginRegistry, executor);

        PluginHandle handle = mock(PluginHandle.class);
        when(handle.pluginId()).thenReturn("restapi");
        when(handle.version()).thenReturn("1.0.1");
        when(handle.nodeDefinitions()).thenReturn(List.of(def()));
        when(handle.nodeDefinition(eq("REST_API_CALL"))).thenReturn(Optional.of(def()));

        when(pluginRegistry.handles()).thenReturn(List.of(handle));
        when(pluginRegistry.findDefault("restapi")).thenReturn(Optional.of(handle));
        when(pluginRegistry.findDefault("nope")).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("available lists installed plugin node types with their AI hint")
    void available() {
        List<AIToolRegistry.ToolDescriptor> tools = registry.available();
        assertThat(tools).singleElement().satisfies(t -> {
            assertThat(t.pluginId()).isEqualTo("restapi");
            assertThat(t.nodeType()).isEqualTo("REST_API_CALL");
            assertThat(t.toolName()).isEqualTo("rest_api_call");
            assertThat(t.supportsAI()).isTrue();
        });
    }

    @Test
    @DisplayName("resolve builds an adapter for a selected, installed tool")
    void resolveSelected() {
        List<AITool> tools = registry.resolve(List.of(
                new AIToolRegistry.ToolSelection("restapi", "REST_API_CALL")));
        assertThat(tools).singleElement()
                .satisfies(t -> assertThat(t.getName()).isEqualTo("rest_api_call"));
    }

    @Test
    @DisplayName("a selected tool whose plugin is not installed simply does not resolve")
    void resolveSkipsMissing() {
        List<AITool> tools = registry.resolve(List.of(
                new AIToolRegistry.ToolSelection("nope", "GONE")));
        assertThat(tools).isEmpty();
    }
}
