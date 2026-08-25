package com.orchpilot.workflow.exception;

/**
 * The requested transition is not legal from the current state, such as executing a draft workflow or
 * resuming a completed execution.
 */
public class InvalidWorkflowStateException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public InvalidWorkflowStateException(String message) {
        super("INVALID_STATE", message);
    }
}
