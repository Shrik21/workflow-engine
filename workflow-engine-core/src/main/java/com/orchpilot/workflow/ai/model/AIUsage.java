package com.orchpilot.workflow.ai.model;

/**
 * Token usage a provider reported, for cost tracking. Zero when the provider does not expose it.
 *
 * @param inputTokens  tokens in the prompt
 * @param outputTokens tokens in the completion
 * @param totalTokens  the sum the provider reported, which may differ from the parts
 */
public record AIUsage(long inputTokens, long outputTokens, long totalTokens) {

    public static AIUsage none() {
        return new AIUsage(0, 0, 0);
    }

    public static AIUsage of(long input, long output) {
        return new AIUsage(input, output, input + output);
    }
}
