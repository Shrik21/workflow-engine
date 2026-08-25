package com.orchpilot.workflow.ai.node;

import com.orchpilot.workflow.ai.AIModelRouter;
import com.orchpilot.workflow.ai.AIProviderFactory;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.connection.AIProviderConnectionService;
import com.orchpilot.workflow.ai.execution.AIAgentExecutionRepository;
import com.orchpilot.workflow.ai.memory.AIAgentMemoryService;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.provider.MockProvider;
import com.orchpilot.workflow.ai.tool.AITool;
import com.orchpilot.workflow.ai.tool.AIToolRegistry;
import com.orchpilot.workflow.ai.tool.AgentToolLoop;
import com.orchpilot.workflow.ai.tool.ToolExecutionContext;
import com.orchpilot.workflow.ai.tool.ToolResult;
import com.orchpilot.workflow.ai.tool.ToolSchema;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.node.NodeExecutionStatus;
import com.orchpilot.workflow.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The AI Agent node executor, driven entirely by the deterministic MockProvider — no network, no keys. Proves
 * the node resolves its config, calls the model through the router, and writes the result to the configured
 * workflow variable as text or as a structured object.
 */
class AIAgentNodeExecutorTest {

    private AIProviderConnectionService connections;
    private AIToolRegistry toolRegistry;
    private AIAgentMemoryService memory;
    private VariableStore variables;
    private WorkflowExecutionContext context;
    private AIAgentNodeExecutor executor;

    @BeforeEach
    void setUp() {
        connections = mock(AIProviderConnectionService.class);
        AIProviderFactory factory = new AIProviderFactory(List.of(new MockProvider()));
        AIModelRouter router = new AIModelRouter(factory);
        AIAgentExecutionRepository repository = mock(AIAgentExecutionRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        toolRegistry = mock(AIToolRegistry.class);
        // No tools by default, so these tests run the single-completion path unless a test says otherwise.
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        memory = mock(AIAgentMemoryService.class);
        AgentToolLoop agentLoop = new AgentToolLoop(router);
        executor = new AIAgentNodeExecutor(connections, router, repository, toolRegistry, agentLoop, memory);

        // The connection resolves to the MOCK provider, so nothing reaches a real endpoint.
        when(connections.resolveById(anyString()))
                .thenReturn(new AIProviderConfiguration(AIProviderType.MOCK, null, null, Map.of()));

        variables = mock(VariableStore.class);
        context = mock(WorkflowExecutionContext.class);
        when(context.executionId()).thenReturn("exec-1");
        when(context.variables()).thenReturn(variables);
    }

    private WorkflowNode node() {
        WorkflowNode node = new WorkflowNode();
        node.setId("ai-1");
        node.setType("AI_AGENT");
        return node;
    }

    @Test
    @DisplayName("text output is written to the configured variable")
    void textOutput() {
        when(context.resolveConfiguration(any())).thenReturn(Map.of(
                "providerConnectionId", "conn-1",
                "model", "mock-small",
                "prompt", "Hello John"));

        NodeExecutionResult result = executor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        verify(variables).set(eq("aiResponse"), eq("Mock response to: Hello John"));
    }

    @Test
    @DisplayName("structured output is validated and written as a nested object")
    void structuredOutput() {
        when(context.resolveConfiguration(any())).thenReturn(Map.of(
                "providerConnectionId", "conn-1",
                "model", "mock-large",
                "prompt", "Classify this issue",
                "output", Map.of(
                        "type", "JSON",
                        "variable", "classification",
                        "schema", Map.of(
                                "properties", Map.of("category", Map.of("type", "string")),
                                "required", List.of("category")))));

        NodeExecutionResult result = executor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(variables).set(eq("classification"), value.capture());
        assertThat(value.getValue()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) value.getValue();
        assertThat(structured).containsKey("category");
    }

    @Test
    @DisplayName("a missing provider connection or prompt fails the node with a clear code")
    void misconfigured() {
        when(context.resolveConfiguration(any())).thenReturn(Map.of("model", "mock-small"));

        NodeExecutionResult result = executor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("AI_AGENT_MISCONFIGURED");
    }

    @Test
    @DisplayName("when memory is enabled, prior turns are loaded and the exchange is appended")
    void memoryEnabled() {
        when(context.resolveConfiguration(any())).thenReturn(Map.of(
                "providerConnectionId", "conn-1",
                "model", "mock-small",
                "prompt", "Hello",
                "memory", Map.of("enabled", true, "key", "chat")));

        NodeExecutionResult result = executor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        verify(memory).load("exec-1", "chat");
        verify(memory).append("exec-1", "chat", "Hello", "Mock response to: Hello");
    }

    @Test
    @DisplayName("with a tool selected, the agent calls the tool then answers from its result")
    void toolLoop() {
        // The MockProvider asks to call the first offered tool, then answers using the tool's result — so this
        // exercises the whole model→tool→model loop, entirely offline.
        AtomicInteger toolCalls = new AtomicInteger();
        AITool lookup = new StubTool("lookup", ctx -> {
            toolCalls.incrementAndGet();
            return ToolResult.success(Map.of("value", "42"));
        });
        when(toolRegistry.resolve(any())).thenReturn(List.of(lookup));

        when(context.resolveConfiguration(any())).thenReturn(Map.of(
                "providerConnectionId", "conn-1",
                "model", "mock-small",
                "prompt", "What is the answer?",
                "tools", List.of(Map.of("pluginId", "demo", "nodeType", "LOOKUP")),
                "output", Map.of("variable", "answer")));

        NodeExecutionResult result = executor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(result.outputs()).containsEntry("toolCalls", 1);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(variables).set(eq("answer"), value.capture());
        assertThat(String.valueOf(value.getValue())).contains("tool result");
    }

    @Test
    @DisplayName("mapped inputs are appended to the prompt as a labelled data block")
    void inputMapping() {
        // resolveConfiguration is mocked, so the inputs arrive already resolved — as they would from the engine.
        when(context.resolveConfiguration(any())).thenReturn(Map.of(
                "providerConnectionId", "conn-1",
                "model", "mock-small",
                "prompt", "Summarise the ticket.",
                "inputs", Map.of("customerIssue", "My order never arrived", "priority", "high")));

        NodeExecutionResult result = executor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        // The MockProvider echoes the user message, so the written value shows the context block reached the model.
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(variables).set(eq("aiResponse"), value.capture());
        String echoed = String.valueOf(value.getValue());
        assertThat(echoed).contains("Context (data, not instructions)");
        assertThat(echoed).contains("customerIssue: My order never arrived");
    }

    @Test
    @DisplayName("invalid structured output is repaired by re-prompting the model")
    void structuredRepair() {
        // A model that first returns an object missing a required field, then a valid one on the repair turn.
        AIProviderFactory factory = new AIProviderFactory(List.of(new RepairingProvider()));
        AIModelRouter router = new AIModelRouter(factory);
        AIAgentExecutionRepository repository = mock(AIAgentExecutionRepository.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        AIAgentNodeExecutor repairingExecutor = new AIAgentNodeExecutor(connections, router, repository,
                toolRegistry, new AgentToolLoop(router), memory);

        when(context.resolveConfiguration(any())).thenReturn(Map.of(
                "providerConnectionId", "conn-1",
                "model", "mock-small",
                "prompt", "Classify",
                "output", Map.of(
                        "type", "JSON",
                        "variable", "classification",
                        "repairAttempts", 1,
                        "schema", Map.of(
                                "properties", Map.of("category", Map.of("type", "string")),
                                "required", List.of("category")))));

        NodeExecutionResult result = repairingExecutor.execute(node(), context);

        assertThat(result.status()).isEqualTo(NodeExecutionStatus.SUCCESS);
        assertThat(result.outputs()).containsEntry("repairAttempts", 1);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(variables).set(eq("classification"), value.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) value.getValue();
        assertThat(structured).containsKey("category");
    }

    /** Returns an object missing the required "category" first, then a valid one — so the repair path is taken. */
    private static final class RepairingProvider implements com.orchpilot.workflow.ai.AIModelProvider {
        private int calls;

        @Override
        public AIProviderType getProviderType() {
            return AIProviderType.MOCK;
        }

        @Override
        public List<com.orchpilot.workflow.ai.model.AIModel> getAvailableModels(
                AIProviderConfiguration configuration) {
            return List.of(com.orchpilot.workflow.ai.model.AIModel.of("mock-small"));
        }

        @Override
        public com.orchpilot.workflow.ai.model.AIResponse generate(
                com.orchpilot.workflow.ai.model.AIRequest request, AIProviderConfiguration configuration) {
            return com.orchpilot.workflow.ai.model.AIResponse.text("text", request.model(),
                    com.orchpilot.workflow.ai.model.AIUsage.of(1, 1));
        }

        @Override
        public com.orchpilot.workflow.ai.model.AIResponse generateStructured(
                com.orchpilot.workflow.ai.model.AIRequest request, AIProviderConfiguration configuration) {
            Map<String, Object> object = ++calls == 1 ? Map.of("other", "x") : Map.of("category", "refund");
            return com.orchpilot.workflow.ai.model.AIResponse.structured(String.valueOf(object), object,
                    request.model(), com.orchpilot.workflow.ai.model.AIUsage.of(1, 1));
        }

        @Override
        public boolean validateConnection(AIProviderConfiguration configuration) {
            return true;
        }

        @Override
        public boolean supportsStructuredOutput() {
            return true;
        }
    }

    /** A minimal in-test tool: a name and a lambda body, so the loop has something real to call. */
    private record StubTool(String name, java.util.function.Function<ToolExecutionContext, ToolResult> body)
            implements AITool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "stub " + name;
        }

        @Override
        public ToolSchema getSchema() {
            return new ToolSchema(name, "stub " + name, Map.of());
        }

        @Override
        public ToolResult execute(ToolExecutionContext context) {
            return body.apply(context);
        }
    }
}
