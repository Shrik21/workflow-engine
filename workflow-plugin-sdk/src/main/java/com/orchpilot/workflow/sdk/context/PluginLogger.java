package com.orchpilot.workflow.sdk.context;

/**
 * Logger handed to plugins, pre-tagged with the plugin id, version and current execution so that
 * plugin output is always attributable.
 *
 * <p>Uses SLF4J-style {@code {}} placeholders. The engine binds this to its own logging backend,
 * so a plugin never needs a logging dependency of its own and cannot reconfigure engine logging.
 *
 * <p>Messages must never contain secrets. The engine redacts known secret values on a best-effort
 * basis, but that is a safety net, not a licence.
 *
 * @since 1.0.0
 */
public interface PluginLogger {

    /**
     * @param message message with {@code {}} placeholders
     * @param args    placeholder arguments
     */
    void debug(String message, Object... args);

    /**
     * @param message message with {@code {}} placeholders
     * @param args    placeholder arguments
     */
    void info(String message, Object... args);

    /**
     * @param message message with {@code {}} placeholders
     * @param args    placeholder arguments
     */
    void warn(String message, Object... args);

    /**
     * @param message message with {@code {}} placeholders
     * @param args    placeholder arguments
     */
    void error(String message, Object... args);

    /**
     * @param message message text
     * @param cause   throwable to log with a stack trace
     */
    void error(String message, Throwable cause);

    /**
     * @return whether debug logging is enabled, for guarding expensive message construction
     */
    boolean isDebugEnabled();
}
