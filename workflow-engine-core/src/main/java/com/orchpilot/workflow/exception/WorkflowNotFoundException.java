package com.orchpilot.workflow.exception;

/**
 * The requested workflow or workflow version does not exist.
 */
public class WorkflowNotFoundException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public WorkflowNotFoundException(String workflowId) {
        super("WORKFLOW_NOT_FOUND", "No workflow with id '" + workflowId + "'");
    }

    public WorkflowNotFoundException(String workflowId, int version) {
        super("WORKFLOW_VERSION_NOT_FOUND",
                "Workflow '" + workflowId + "' has no version " + version);
    }
}
