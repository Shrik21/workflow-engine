package com.orchpilot.workflow.ai.cli;

/**
 * An AI CLI configuration, detection or execution failure, carrying a stable code for the UI.
 */
public class AiCliException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public AiCliException(String errorCode, String message) {
        this(errorCode, message, false);
    }

    public AiCliException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    /** The feature is switched off at the host, so nothing may be executed. */
    public static AiCliException disabled() {
        return new AiCliException("AI_CLI_DISABLED",
                "AI CLI execution is disabled on this engine. An operator must set "
                        + "workflow.engine.ai.cli.enabled=true in the engine's configuration; it cannot be "
                        + "turned on from the user interface.");
    }
}
