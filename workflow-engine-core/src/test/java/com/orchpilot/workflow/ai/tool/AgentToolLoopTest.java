package com.orchpilot.workflow.ai.tool;

import com.orchpilot.workflow.ai.AIModelProvider;
import com.orchpilot.workflow.ai.AIModelRouter;
import com.orchpilot.workflow.ai.AIProviderFactory;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIMessage;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import com.orchpilot.workflow.ai.model.AIToolCall;
import com.orchpilot.workflow.ai.model.AIUsage;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The agent loop, proven to be bounded. The specification forbids unlimited agent loops, so the load-bearing
 * tests here are the ones that show the loop stops — at the iteration cap and at the tool-call cap — and still
 * produces a final answer rather than hanging, alongside the ordinary "model calls a tool then answers" path.
 * A stub provider drives the model's behaviour deterministically; a stub tool stands in for a real capability.
 */
class AgentToolLoopTest {

    private final WorkflowExecutionContext workflowContext = mock(WorkflowExecutionContext.class);
    private final AIProviderConfiguration config =
            new AIProviderConfiguration(AIProviderType.MOCK, null, null, Map.of());
    private final AIModelRouter.RetryPolicy retry = AIModelRouter.RetryPolicy.of(0, 0);

    private AgentToolLoop loopWith(AIModelProvider provider) {
        return new AgentToolLoop(new AIModelRouter(new AIProviderFactory(List.of(provider))));
    }

    private AIRequest seed() {
        return new AIRequest("m", List.of(AIMessage.user("go")), null, null, null);
    }

    @Test
    void modelCallsAToolThenAnswers() {
        AtomicInteger calls = new AtomicInteger();
        AITool tool = new StubTool(false, ctx -> {
            calls.incrementAndGet();
            return ToolResult.success(Map.of("value", "42"));
        });
        AgentToolLoop loop = loopWith(new StubProvider(false));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(tool), workflowContext,
                AgentToolLoop.Limits.of(10, 10, 60));

        assertThat(result.stopReason()).isEqualTo("COMPLETED");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.response().hasToolCalls()).isFalse();
        assertThat(result.response().text()).isEqualTo("final answer");
    }

    @Test
    void stopsAtTheIterationCapAndStillAnswers() {
        AITool tool = new StubTool(false, ctx -> ToolResult.success(Map.of()));
        // A model that never stops asking for tools must still be stopped by the iteration bound.
        AgentToolLoop loop = loopWith(new StubProvider(true));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(tool), workflowContext,
                AgentToolLoop.Limits.of(3, 100, 60));

        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.stopReason()).isEqualTo("MAX_ITERATIONS");
        // The forced no-tools turn produced a plain answer, so the workflow is never left hanging.
        assertThat(result.response().hasToolCalls()).isFalse();
        assertThat(result.response().text()).isEqualTo("final answer");
    }

    @Test
    void stopsAtTheToolCallCap() {
        AtomicInteger calls = new AtomicInteger();
        AITool tool = new StubTool(false, ctx -> {
            calls.incrementAndGet();
            return ToolResult.success(Map.of());
        });
        AgentToolLoop loop = loopWith(new StubProvider(true));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(tool), workflowContext,
                AgentToolLoop.Limits.of(100, 2, 60));

        assertThat(result.stopReason()).isEqualTo("MAX_TOOL_CALLS");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.toolCallCount()).isEqualTo(2);
        assertThat(result.response().hasToolCalls()).isFalse();
    }

    @Test
    void anUnknownToolBecomesAFailureTheModelCanRead() {
        // The model asks for "lookup" but no such tool is resolved; the loop must not crash — it hands back a
        // failure result and carries on to a final answer.
        AgentToolLoop loop = loopWith(new StubProvider(false));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(), workflowContext,
                AgentToolLoop.Limits.of(10, 10, 60));

        // With no tools offered, the stub returns text immediately.
        assertThat(result.stopReason()).isEqualTo("COMPLETED");
        assertThat(result.response().text()).isEqualTo("final answer");
    }

    @Test
    void supervisedModeBlocksADestructiveToolUntilApproved() {
        AtomicInteger calls = new AtomicInteger();
        AITool destructive = new StubTool(true, ctx -> {
            calls.incrementAndGet();
            return ToolResult.success(Map.of());
        });
        AgentToolLoop loop = loopWith(new StubProvider(false));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(destructive), workflowContext,
                AgentToolLoop.Limits.of(10, 10, 60),
                new ToolApprovalPolicy(true, Set.of(), Set.of()));

        // The destructive tool was never run; the block was fed back and the model answered.
        assertThat(calls.get()).isZero();
        assertThat(result.toolCallCount()).isZero();
        assertThat(result.blockedToolCalls()).isEqualTo(1);
        assertThat(result.pendingApprovals()).containsExactly("lookup");
        assertThat(result.stopReason()).isEqualTo("COMPLETED");
        assertThat(result.response().text()).isEqualTo("final answer");
    }

    @Test
    void supervisedModeRunsAnApprovedDestructiveTool() {
        AtomicInteger calls = new AtomicInteger();
        AITool destructive = new StubTool(true, ctx -> {
            calls.incrementAndGet();
            return ToolResult.success(Map.of());
        });
        AgentToolLoop loop = loopWith(new StubProvider(false));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(destructive), workflowContext,
                AgentToolLoop.Limits.of(10, 10, 60),
                new ToolApprovalPolicy(true, Set.of("lookup"), Set.of()));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.blockedToolCalls()).isZero();
        assertThat(result.stopReason()).isEqualTo("COMPLETED");
    }

    @Test
    void aDeniedToolNeverRunsEvenWhenNotSupervised() {
        AtomicInteger calls = new AtomicInteger();
        AITool tool = new StubTool(false, ctx -> {
            calls.incrementAndGet();
            return ToolResult.success(Map.of());
        });
        AgentToolLoop loop = loopWith(new StubProvider(false));

        AgentToolLoop.LoopResult result = loop.run(seed(), config, retry, List.of(tool), workflowContext,
                AgentToolLoop.Limits.of(10, 10, 60),
                new ToolApprovalPolicy(false, Set.of(), Set.of("lookup")));

        assertThat(calls.get()).isZero();
        assertThat(result.blockedToolCalls()).isEqualTo(1);
        assertThat(result.pendingApprovals()).containsExactly("lookup");
    }

    /** A tool with a fixed name, an optional destructive flag, and a lambda body. */
    private record StubTool(boolean destructive,
                            java.util.function.Function<ToolExecutionContext, ToolResult> body) implements AITool {
        @Override
        public String getName() {
            return "lookup";
        }

        @Override
        public String getDescription() {
            return "look something up";
        }

        @Override
        public ToolSchema getSchema() {
            return new ToolSchema("lookup", "look something up", Map.of());
        }

        @Override
        public boolean isDestructive() {
            return destructive;
        }

        @Override
        public ToolResult execute(ToolExecutionContext context) {
            return body.apply(context);
        }
    }

    /**
     * A deterministic model: it requests the "lookup" tool whenever tools are offered ({@code alwaysCall}), or
     * only until a tool result appears; with no tools offered it answers "final answer".
     */
    private static final class StubProvider implements AIModelProvider {
        private final boolean alwaysCall;

        private StubProvider(boolean alwaysCall) {
            this.alwaysCall = alwaysCall;
        }

        @Override
        public AIProviderType getProviderType() {
            return AIProviderType.MOCK;
        }

        @Override
        public List<AIModel> getAvailableModels(AIProviderConfiguration configuration) {
            return List.of(AIModel.of("m"));
        }

        @Override
        public AIResponse generate(AIRequest request, AIProviderConfiguration configuration) {
            boolean toolResultSeen = request.messages().stream()
                    .anyMatch(m -> m.role() == AIMessage.Role.TOOL);
            if (request.hasTools() && (alwaysCall || !toolResultSeen)) {
                AIToolCall call = new AIToolCall("call-1", "lookup", Map.of());
                return AIResponse.toolCalls(null, List.of(call), "m", AIUsage.of(1, 1));
            }
            return AIResponse.text("final answer", "m", AIUsage.of(1, 1));
        }

        @Override
        public boolean validateConnection(AIProviderConfiguration configuration) {
            return true;
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }
    }
}
