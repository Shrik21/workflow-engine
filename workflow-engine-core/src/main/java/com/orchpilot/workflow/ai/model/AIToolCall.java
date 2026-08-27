package com.orchpilot.workflow.ai.model;

import java.util.Map;

/**
 * A single tool invocation the model asked for, in provider-independent form.
 *
 * <p>Every provider expresses "the model wants to call a tool" differently — OpenAI's {@code tool_calls}, Claude's
 * {@code tool_use} content block, Gemini's {@code functionCall}. Each adapter parses its own shape into this record
 * and, on the next turn, renders this record back into its own shape, so the agent loop that sits above them
 * reasons in one vocabulary and never learns any provider's wire format.
 *
 * @param id        the provider's id for this call, echoed back with the result so the model can correlate them
 * @param name      the tool the model chose
 * @param arguments the arguments the model produced for the tool's parameters
 */
public record AIToolCall(String id, String name, Map<String, Object> arguments) {

    public AIToolCall {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
