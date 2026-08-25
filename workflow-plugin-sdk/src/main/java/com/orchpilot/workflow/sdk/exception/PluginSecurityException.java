package com.orchpilot.workflow.sdk.exception;

/**
 * Thrown when a plugin attempts something its declared permissions do not allow, such as reading a
 * secret outside its scope or calling a host that is not on its allowlist.
 *
 * <p>These are policy violations, not transient faults, and are always audited by the engine.
 *
 * @since 1.0.0
 */
public class PluginSecurityException extends PluginException {

    private static final long serialVersionUID = 1L;

    /** Error code reported for every instance of this exception. */
    public static final String CODE = "PLUGIN_PERMISSION_DENIED";

    public PluginSecurityException(String message) {
        super(CODE, message, false, null);
    }
}
