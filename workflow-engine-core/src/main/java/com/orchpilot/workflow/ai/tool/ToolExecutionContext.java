package com.orchpilot.workflow.ai.tool;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;

import java.util.Map;

/**
 * Everything a tool needs to run, passed to {@link AITool#execute}.
 *
 * <p>It carries the live workflow execution context — the same one every node runs against — so a plugin-backed
 * tool executes with exactly the identity, tenant, variables and permissions of the workflow that invoked it.
 * That is what keeps a tool inside the platform's existing boundaries: an AI Agent cannot make a tool do
 * anything the workflow itself could not do, and the tenant/authorization guarantees the engine already enforces
 * apply unchanged. The arguments are the values the model chose for the tool's parameters.
 *
 * @param workflowContext the execution the tool runs within
 * @param arguments       the tool arguments the model produced
 */
public record ToolExecutionContext(WorkflowExecutionContext workflowContext, Map<String, Object> arguments) {

    public ToolExecutionContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public String executionId() {
        return workflowContext.executionId();
    }

    public String workflowId() {
        return workflowContext.workflowId();
    }
}
