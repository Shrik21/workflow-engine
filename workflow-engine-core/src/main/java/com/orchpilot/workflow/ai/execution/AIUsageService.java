package com.orchpilot.workflow.ai.execution;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Summarises AI Agent token usage from the execution records for reporting.
 *
 * <h2>Reporting off the record that already exists</h2>
 *
 * Every AI Agent run already writes an {@link AIAgentExecution} with its provider, model, timing and token counts
 * — and, by design, never its prompt or response. So usage reporting needs no second store and no new capture
 * path: it aggregates those metadata records into totals and per-provider / per-model breakdowns. Because the
 * record holds only metadata, the report is safe to show to anyone who may configure AI ({@code AI_PROVIDER_VIEW})
 * without exposing anything sensitive.
 */
@Service
public class AIUsageService {

    private final AIAgentExecutionRepository executions;

    public AIUsageService(AIAgentExecutionRepository executions) {
        this.executions = executions;
    }

    /** A totals-and-breakdowns summary of AI usage. */
    public record UsageSummary(long executions, long inputTokens, long outputTokens, long totalTokens,
                               long toolCalls, List<Breakdown> byProvider, List<Breakdown> byModel) {
    }

    /** Usage grouped under one provider or model. */
    public record Breakdown(String name, long executions, long totalTokens) {
    }

    public UsageSummary summary() {
        long execs = 0;
        long input = 0;
        long output = 0;
        long total = 0;
        long tools = 0;
        Map<String, long[]> byProvider = new LinkedHashMap<>();
        Map<String, long[]> byModel = new LinkedHashMap<>();

        for (AIAgentExecution record : executions.findAll()) {
            execs++;
            input += record.getInputTokens();
            output += record.getOutputTokens();
            total += record.getTotalTokens();
            tools += record.getToolCalls();
            accumulate(byProvider, record.getProvider(), record.getTotalTokens());
            accumulate(byModel, record.getModel(), record.getTotalTokens());
        }

        return new UsageSummary(execs, input, output, total, tools,
                breakdowns(byProvider), breakdowns(byModel));
    }

    /** The most recent runs, newest first, for the history view. */
    public List<AIAgentExecution> recent() {
        return executions.findTop200ByOrderByStartedAtDesc();
    }

    private static void accumulate(Map<String, long[]> into, String key, long tokens) {
        long[] counters = into.computeIfAbsent(key == null ? "unknown" : key, k -> new long[2]);
        counters[0]++;
        counters[1] += tokens;
    }

    private static List<Breakdown> breakdowns(Map<String, long[]> grouped) {
        List<Breakdown> out = new ArrayList<>();
        grouped.forEach((name, counters) -> out.add(new Breakdown(name, counters[0], counters[1])));
        out.sort(Comparator.comparingLong(Breakdown::totalTokens).reversed());
        return out;
    }
}
