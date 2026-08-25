package com.orchpilot.workflow.ai.tool;

/**
 * A capability an AI Agent may be given — a named, described, schema-bearing action the model can invoke.
 *
 * <h2>Tools are not built into the agent</h2>
 *
 * The specification is explicit that tools must not be implemented inside the AI Agent. This interface is the
 * seam: the agent knows only {@code AITool}, and the concrete tools come from elsewhere — in this phase, from
 * {@link PluginAIToolAdapter}, which turns an installed OrchPilot plugin into a tool, so the platform's plugins
 * become an agent's capabilities without the agent knowing what a plugin is.
 */
public interface AITool {

    /** @return a stable, model-friendly name */
    String getName();

    /** @return what the tool does, for the model to decide when to use it */
    String getDescription();

    /** @return the tool's name, description and parameter schema, as the model is shown it */
    ToolSchema getSchema();

    /**
     * @return whether this tool has consequential side effects that warrant human approval in a supervised agent
     *
     * <p>Default false. A destructive tool (deletes data, sends a message, moves money) is not run by a supervised
     * agent until it is approved; see {@link ToolApprovalPolicy}. It does not change what the tool does once run.
     */
    default boolean isDestructive() {
        return false;
    }

    /**
     * Executes the tool.
     *
     * @param context the execution to run within and the arguments the model chose
     * @return the result, success or failure, as data for the model
     */
    ToolResult execute(ToolExecutionContext context);
}
