package com.orchpilot.workflow.exception;

/**
 * The requested execution does not exist.
 */
public class ExecutionNotFoundException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public ExecutionNotFoundException(String executionId) {
        super("EXECUTION_NOT_FOUND", "No execution with id '" + executionId + "'");
    }
}
