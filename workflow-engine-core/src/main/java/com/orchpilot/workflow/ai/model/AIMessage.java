package com.orchpilot.workflow.ai.model;

import java.util.List;

/**
 * One message in a conversation with a model.
 *
 * <p>The role is the whole point of keeping messages separate rather than concatenating a prompt: it is what
 * lets the runtime keep <em>system instructions</em>, <em>user input</em>, <em>workflow data</em> and <em>tool
 * output</em> in distinct channels, so untrusted data returned by a tool or a REST call can never be presented
 * to the model as a system instruction. That separation is the basis of the prompt-injection protection the
 * specification requires.
 *
 * <p>For the tool-calling loop a message also carries two optional things, still provider-independently: an
 * {@code ASSISTANT} message may hold the {@link AIToolCall}s the model asked for, and a {@code TOOL} message
 * carries the {@code toolCallId} it answers. Each provider adapter renders these into its own wire shape; the
 * loop above never sees that shape.
 *
 * @param role       who the message is from
 * @param content    the message text; may be null for an assistant turn that only requested tools
 * @param toolCalls  the tool calls an assistant turn requested; empty otherwise
 * @param toolCallId the id of the call a tool-result message answers; null otherwise
 */
public record AIMessage(Role role, String content, List<AIToolCall> toolCalls, String toolCallId) {

    /** Who a message is from. */
    public enum Role {
        /** The immutable instructions that define the agent; never overwritten by data. */
        SYSTEM,
        /** The human/workflow request. */
        USER,
        /** The model's own reply. */
        ASSISTANT,
        /** The result of a tool call, handed back to the model as data. */
        TOOL
    }

    public AIMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** The plain two-part message the rest of the system builds; no tool calls, no tool-call id. */
    public AIMessage(Role role, String content) {
        this(role, content, List.of(), null);
    }

    public static AIMessage system(String content) {
        return new AIMessage(Role.SYSTEM, content);
    }

    public static AIMessage user(String content) {
        return new AIMessage(Role.USER, content);
    }

    public static AIMessage assistant(String content) {
        return new AIMessage(Role.ASSISTANT, content);
    }

    /** The assistant turn that asked to call one or more tools, echoed back on the next request. */
    public static AIMessage assistantToolCalls(String content, List<AIToolCall> toolCalls) {
        return new AIMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    /** A tool's result, handed back to the model as data against the call it answers. */
    public static AIMessage toolResult(String toolCallId, String content) {
        return new AIMessage(Role.TOOL, content, List.of(), toolCallId);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
