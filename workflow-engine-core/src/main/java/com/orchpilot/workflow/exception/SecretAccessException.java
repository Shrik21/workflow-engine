package com.orchpilot.workflow.exception;

/**
 * A secret could not be read or written: it is out of scope for the caller, or the engine has no
 * master key configured.
 */
public class SecretAccessException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public SecretAccessException(String message) {
        super("SECRET_ACCESS_DENIED", message);
    }

    public SecretAccessException(String message, Throwable cause) {
        super("SECRET_ACCESS_DENIED", message, cause);
    }
}
