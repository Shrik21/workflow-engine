package com.orchpilot.workflow.ai.model;

import java.util.List;
import java.util.Map;

/**
 * A provider-independent request to generate a completion.
 *
 * <p>Deliberately the only shape the engine hands a provider: messages with roles (never a flattened prompt, so
 * the injection boundary survives), the model id, the sampling controls, an optional JSON schema for structured
 * output, and — when the agent may act — the {@link AIToolSpec tools} it is offered. A provider adapter
 * translates this into its own wire format; the engine never sees that format. A provider that does not support
 * tools simply ignores {@link #tools()} and never returns a tool call, so the loop above degrades to a single
 * completion.
 *
 * @param model        the model id to call
 * @param messages     the conversation, roles intact
 * @param temperature  sampling temperature, or null for the provider default
 * @param maxTokens    completion ceiling, or null for the provider default
 * @param jsonSchema   a JSON schema the output must match, for structured output; null for free text
 * @param tools        the tools offered to the model this turn; empty for a plain completion
 */
public record AIRequest(String model, List<AIMessage> messages, Double temperature, Integer maxTokens,
                        Map<String, Object> jsonSchema, List<AIToolSpec> tools) {

    public AIRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** The common request with no tools offered. */
    public AIRequest(String model, List<AIMessage> messages, Double temperature, Integer maxTokens,
                     Map<String, Object> jsonSchema) {
        this(model, messages, temperature, maxTokens, jsonSchema, List.of());
    }

    /** @return whether this request asks for schema-constrained JSON */
    public boolean isStructured() {
        return jsonSchema != null && !jsonSchema.isEmpty();
    }

    /** @return whether any tools are offered on this request */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /** A builder for the common case. */
    public static AIRequest of(String model, List<AIMessage> messages, Double temperature, Integer maxTokens) {
        return new AIRequest(model, messages, temperature, maxTokens, null, List.of());
    }
}
