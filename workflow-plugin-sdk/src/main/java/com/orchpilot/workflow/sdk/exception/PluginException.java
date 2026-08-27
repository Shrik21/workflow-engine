package com.orchpilot.workflow.sdk.exception;

/**
 * Base class for every failure a plugin may surface to the engine.
 *
 * <p>Carries a stable, machine-readable {@code errorCode} so that workflow authors can branch on
 * failure kinds without string-matching human-readable messages.
 *
 * @since 1.0.0
 */
public class PluginException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean retryable;

    /**
     * @param errorCode stable, upper-snake-case failure identifier, e.g. {@code API_TIMEOUT}
     * @param message   human-readable description; must not contain secrets
     */
    public PluginException(String errorCode, String message) {
        this(errorCode, message, false, null);
    }

    /**
     * @param errorCode stable failure identifier
     * @param message   human-readable description; must not contain secrets
     * @param cause     underlying failure, may be {@code null}
     */
    public PluginException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, false, cause);
    }

    /**
     * @param errorCode stable failure identifier
     * @param message   human-readable description; must not contain secrets
     * @param retryable {@code true} when a later identical attempt could succeed
     * @param cause     underlying failure, may be {@code null}
     */
    public PluginException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? "PLUGIN_ERROR" : errorCode;
        this.retryable = retryable;
    }

    /**
     * @return stable, machine-readable failure identifier
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * @return whether the engine's retry policy should consider re-attempting this node
     */
    public boolean isRetryable() {
        return retryable;
    }
}
