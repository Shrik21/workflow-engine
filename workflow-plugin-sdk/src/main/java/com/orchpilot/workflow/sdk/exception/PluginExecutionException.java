package com.orchpilot.workflow.sdk.exception;

/**
 * Thrown when a plugin fails while performing its work, as opposed to failing validation.
 *
 * @since 1.0.0
 */
public class PluginExecutionException extends PluginException {

    private static final long serialVersionUID = 1L;

    public PluginExecutionException(String errorCode, String message) {
        super(errorCode, message, false, null);
    }

    public PluginExecutionException(String errorCode, String message, boolean retryable) {
        super(errorCode, message, retryable, null);
    }

    public PluginExecutionException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(errorCode, message, retryable, cause);
    }
}
