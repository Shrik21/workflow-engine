package com.orchpilot.workflow.ai.model;

import java.util.List;
import java.util.Map;

/**
 * A provider-independent completion.
 *
 * <p>A completion is one of two things, and the loop above tells them apart by {@link #hasToolCalls()}: an
 * answer (text, or parsed structured JSON), or a request from the model to call one or more {@link AIToolCall
 * tools} before it can answer. Every provider maps its own "the model wants a tool" shape onto {@code toolCalls},
 * so the agent loop reads one vocabulary regardless of who answered.
 *
 * @param text         the text the model produced; may be null when it only requested tools
 * @param structured   the parsed JSON object, when structured output was requested and returned; else null
 * @param model        the model that actually answered
 * @param usage        token usage, or {@link AIUsage#none()} when the provider did not report it
 * @param finishReason why the model stopped, as the provider reported it
 * @param toolCalls    the tools the model asked to call; empty when it produced a final answer
 */
public record AIResponse(String text, Map<String, Object> structured, String model, AIUsage usage,
                         String finishReason, List<AIToolCall> toolCalls) {

    public AIResponse {
        usage = usage == null ? AIUsage.none() : usage;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AIResponse text(String text, String model, AIUsage usage) {
        return new AIResponse(text, null, model, usage, "stop", List.of());
    }

    public static AIResponse structured(String text, Map<String, Object> structured, String model,
                                        AIUsage usage) {
        return new AIResponse(text, structured, model, usage, "stop", List.of());
    }

    /** A response in which the model asked to call tools rather than answer. */
    public static AIResponse toolCalls(String text, List<AIToolCall> toolCalls, String model, AIUsage usage) {
        return new AIResponse(text, null, model, usage, "tool_use", toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
