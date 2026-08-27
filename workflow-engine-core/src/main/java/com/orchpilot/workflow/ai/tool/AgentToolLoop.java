package com.orchpilot.workflow.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchpilot.workflow.ai.AIModelRouter;
import com.orchpilot.workflow.ai.model.AIMessage;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.ai.model.AIRequest;
import com.orchpilot.workflow.ai.model.AIResponse;
import com.orchpilot.workflow.ai.model.AIToolCall;
import com.orchpilot.workflow.ai.model.AIToolSpec;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reason-act loop that lets an AI Agent actually <em>use</em> its tools: model → tool → model, until the
 * model answers or a bound is hit.
 *
 * <h2>Bounded by construction — never a runaway agent</h2>
 *
 * The specification forbids unlimited agent loops, so every run is fenced three ways at once: a maximum number of
 * model turns, a maximum number of tool calls across the whole run, and a wall-clock timeout. Whichever binds
 * first stops the loop, and the agent is then asked once more <em>with no tools offered</em>, forcing it to
 * conclude with a plain answer rather than leaving the workflow hanging. Tool output re-enters the conversation
 * only in the {@code TOOL} role — never as a system instruction — so a tool (or a compromised downstream service)
 * cannot rewrite the agent's instructions. The loop executes tools through the {@link AITool} abstraction and
 * knows nothing of plugins, providers or wire formats.
 */
@Component
public class AgentToolLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentToolLoop.class);

    private final AIModelRouter router;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentToolLoop(AIModelRouter router) {
        this.router = router;
    }

    /** The three bounds every run is fenced by; whichever binds first ends the loop. */
    public record Limits(int maxIterations, int maxToolCalls, long timeoutMillis) {

        public static Limits of(Integer maxIterations, Integer maxToolCalls, Integer timeoutSeconds) {
            int iterations = maxIterations == null || maxIterations <= 0 ? 5 : Math.min(maxIterations, 25);
            int calls = maxToolCalls == null || maxToolCalls <= 0 ? 10 : Math.min(maxToolCalls, 50);
            long timeout = (timeoutSeconds == null || timeoutSeconds <= 0 ? 60L : Math.min(timeoutSeconds, 600L))
                    * 1000L;
            return new Limits(iterations, calls, timeout);
        }
    }

    /** One tool call the loop made, for the execution record — the tool and whether it succeeded, never its data. */
    public record ToolInvocation(String tool, boolean success, String error) {
    }

    /** The outcome of a loop: the final answer, how it got there, and why it stopped. */
    public record LoopResult(AIResponse response, int iterations, int toolCallCount,
                             List<ToolInvocation> invocations, String stopReason,
                             long inputTokens, long outputTokens, int blockedToolCalls,
                             List<String> pendingApprovals) {
    }

    /**
     * Runs the loop.
     *
     * @param seed            the initial request (model, system+user messages, sampling); its tools are ignored —
     *                        the {@code tools} argument is authoritative
     * @param configuration   the resolved provider connection
     * @param retry           per-provider retry behaviour for each model turn
     * @param tools           the agent's resolved tools; the loop offers exactly these, nothing automatic
     * @param workflowContext the execution the tools run within — their identity, tenant and permissions
     * @param limits          the iteration / tool-call / timeout bounds
     * @return the final answer plus the run's metadata
     */
    /** Runs autonomously — every tool that is not denied may run. See the policy-taking overload for supervision. */
    public LoopResult run(AIRequest seed, AIProviderConfiguration configuration, AIModelRouter.RetryPolicy retry,
                          List<AITool> tools, WorkflowExecutionContext workflowContext, Limits limits) {
        return run(seed, configuration, retry, tools, workflowContext, limits, ToolApprovalPolicy.autonomous());
    }

    public LoopResult run(AIRequest seed, AIProviderConfiguration configuration, AIModelRouter.RetryPolicy retry,
                          List<AITool> tools, WorkflowExecutionContext workflowContext, Limits limits,
                          ToolApprovalPolicy policy) {
        List<AIMessage> messages = new ArrayList<>(seed.messages());
        List<AIToolSpec> specs = specs(tools);
        Map<String, AITool> byName = new LinkedHashMap<>();
        for (AITool tool : tools) {
            byName.putIfAbsent(tool.getName(), tool);
        }

        List<ToolInvocation> invocations = new ArrayList<>();
        List<String> pendingApprovals = new ArrayList<>();
        long inputTokens = 0;
        long outputTokens = 0;
        int toolCallCount = 0;
        int blockedToolCalls = 0;
        int iterations = 0;
        String stopReason = "COMPLETED";
        long deadline = System.currentTimeMillis() + limits.timeoutMillis();

        AIResponse response = null;
        boolean stop = false;
        while (iterations < limits.maxIterations() && !stop) {
            if (System.currentTimeMillis() > deadline) {
                stopReason = "TIMEOUT";
                break;
            }
            iterations++;

            AIRequest request = new AIRequest(seed.model(), messages, seed.temperature(), seed.maxTokens(),
                    null, specs);
            response = router.execute(new AIModelRouter.Attempt(request, configuration), List.of(), retry, false);
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();

            if (!response.hasToolCalls()) {
                stopReason = "COMPLETED";
                break;
            }

            // Echo the model's own tool-call turn back, then run each call and hand the result back as TOOL data.
            messages.add(AIMessage.assistantToolCalls(response.text(), response.toolCalls()));
            for (AIToolCall call : response.toolCalls()) {
                AITool tool = byName.get(call.name());

                // The destructive-action gate. A blocked call never runs and never counts toward the tool-call
                // budget — it is returned to the model as data so a supervised agent can adapt or explain.
                ToolApprovalPolicy.Decision decision = tool == null
                        ? ToolApprovalPolicy.Decision.allow() : policy.evaluate(tool);
                if (!decision.allowed()) {
                    blockedToolCalls++;
                    if (!pendingApprovals.contains(call.name())) {
                        pendingApprovals.add(call.name());
                    }
                    invocations.add(new ToolInvocation(call.name(), false, decision.reason()));
                    messages.add(AIMessage.toolResult(call.id(), serialize(ToolResult.failure(decision.reason()))));
                    continue;
                }

                if (toolCallCount >= limits.maxToolCalls()) {
                    stopReason = "MAX_TOOL_CALLS";
                    stop = true;
                    break;
                }
                ToolResult result = invoke(tool, call, workflowContext);
                toolCallCount++;
                invocations.add(new ToolInvocation(call.name(), result.success(), result.error()));
                messages.add(AIMessage.toolResult(call.id(), serialize(result)));
            }
        }

        if (!stop && response != null && response.hasToolCalls()) {
            stopReason = "MAX_ITERATIONS";
        }

        // If we stopped while the model still wanted to act, ask once more with no tools so it must conclude.
        if (response == null || response.hasToolCalls()) {
            AIRequest finalRequest = new AIRequest(seed.model(), messages, seed.temperature(), seed.maxTokens(),
                    null, List.of());
            response = router.execute(new AIModelRouter.Attempt(finalRequest, configuration), List.of(),
                    retry, false);
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();
        }

        log.info("AI agent loop finished: {} iteration(s), {} tool call(s), {} blocked, stop reason {}",
                iterations, toolCallCount, blockedToolCalls, stopReason);
        return new LoopResult(response, iterations, toolCallCount, invocations, stopReason,
                inputTokens, outputTokens, blockedToolCalls, pendingApprovals);
    }

    private ToolResult invoke(AITool tool, AIToolCall call, WorkflowExecutionContext workflowContext) {
        if (tool == null) {
            return ToolResult.failure("Unknown tool: " + call.name());
        }
        try {
            ToolResult result = tool.execute(new ToolExecutionContext(workflowContext, call.arguments()));
            return result == null ? ToolResult.failure("The tool returned no result.") : result;
        } catch (RuntimeException ex) {
            // A tool failure is data the model can react to, not a loop-ending exception.
            return ToolResult.failure(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private static List<AIToolSpec> specs(List<AITool> tools) {
        List<AIToolSpec> specs = new ArrayList<>();
        for (AITool tool : tools) {
            specs.add(new AIToolSpec(tool.getName(), tool.getDescription(), tool.getSchema().parameters()));
        }
        return specs;
    }

    /** A tool result becomes a compact JSON string for the model — untrusted data, carried in the TOOL role. */
    private String serialize(ToolResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        if (result.success()) {
            payload.put("output", result.output());
        } else {
            payload.put("error", result.error());
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return String.valueOf(result.success() ? result.output() : result.error());
        }
    }
}
