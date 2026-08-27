package com.orchpilot.workflow.sdk.exception;

/**
 * Thrown when a node's configuration is missing a required value or holds an unusable one.
 *
 * <p>Never retryable: the same configuration will fail identically on every attempt.
 *
 * @since 1.0.0
 */
public class PluginConfigurationException extends PluginException {

    private static final long serialVersionUID = 1L;

    /** Error code reported for every instance of this exception. */
    public static final String CODE = "PLUGIN_CONFIGURATION_INVALID";

    public PluginConfigurationException(String message) {
        super(CODE, message, false, null);
    }

    public PluginConfigurationException(String message, Throwable cause) {
        super(CODE, message, false, cause);
    }
}
