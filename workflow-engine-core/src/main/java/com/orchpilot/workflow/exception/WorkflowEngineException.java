package com.orchpilot.workflow.exception;

/**
 * Base class for engine failures that map to a meaningful API response.
 *
 * <p>Carries a stable {@code errorCode} so that clients branch on codes rather than on message text,
 * and so that a message can be improved without breaking an integration.
 */
public class WorkflowEngineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public WorkflowEngineException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkflowEngineException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** @return stable, machine-readable failure identifier */
    public String getErrorCode() {
        return errorCode;
    }
}
