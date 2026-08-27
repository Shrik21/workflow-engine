package com.orchpilot.workflow.ai.execution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * AI usage reporting and execution history.
 *
 * <p>Read-only, and safe to show to anyone who configures AI ({@code AI_PROVIDER_VIEW}): every field here comes
 * from the {@link AIAgentExecution} metadata record, which by design never holds a prompt or a response. The
 * summary aggregates token usage; the history lists recent runs with their provider, model, timing, token counts,
 * tool-call count and outcome.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI usage", description = "AI Agent token usage and execution history")
public class AIUsageController {

    private final AIUsageService usage;

    public AIUsageController(AIUsageService usage) {
        this.usage = usage;
    }

    /** One AI Agent run, as history — metadata only, never a prompt or a response. */
    public record ExecutionView(String id, String workflowExecutionId, String nodeId, String provider,
                                String model, String status, Instant startedAt, Instant completedAt,
                                long inputTokens, long outputTokens, long totalTokens, int toolCalls,
                                int blockedToolCalls, int iterations, int repairAttempts, String stopReason,
                                String error) {

        static ExecutionView of(AIAgentExecution e) {
            return new ExecutionView(e.getId(), e.getWorkflowExecutionId(), e.getNodeId(), e.getProvider(),
                    e.getModel(), e.getStatus(), e.getStartedAt(), e.getCompletedAt(), e.getInputTokens(),
                    e.getOutputTokens(), e.getTotalTokens(), e.getToolCalls(), e.getBlockedToolCalls(),
                    e.getIterations(), e.getRepairAttempts(), e.getStopReason(), e.getError());
        }
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_VIEW')")
    @GetMapping("/usage")
    @Operation(summary = "Summarise AI Agent token usage across all runs")
    public AIUsageService.UsageSummary usage() {
        return usage.summary();
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_VIEW')")
    @GetMapping("/executions")
    @Operation(summary = "List recent AI Agent runs (metadata only — never prompts or responses)")
    public List<ExecutionView> executions() {
        return usage.recent().stream().map(ExecutionView::of).toList();
    }
}
