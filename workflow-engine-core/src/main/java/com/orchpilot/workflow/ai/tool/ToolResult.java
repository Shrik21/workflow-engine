package com.orchpilot.workflow.ai.tool;

import java.util.Map;

/**
 * The outcome of a tool call, to be handed back to the model as data.
 *
 * <p>A failure is a normal outcome, not an exception: a tool that errors returns a {@link #failure} the model
 * can read and react to (try another tool, ask for more information), which is what makes an agent robust rather
 * than brittle. The output is always treated as untrusted data by the runtime — never as instructions.
 *
 * @param success whether the tool completed successfully
 * @param output  the tool's result, keyed for the model
 * @param error   a readable error when {@code success} is false
 */
public record ToolResult(boolean success, Map<String, Object> output, String error) {

    public ToolResult {
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public static ToolResult success(Map<String, Object> output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, Map.of(), error);
    }
}
