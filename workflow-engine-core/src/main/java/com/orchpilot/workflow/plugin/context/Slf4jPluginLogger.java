package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.PluginLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binds plugin logging to the engine's SLF4J backend.
 *
 * <p>Every message is prefixed with the plugin coordinate, so plugin output is attributable in a shared
 * log without the plugin having to remember to identify itself. Messages pass through
 * {@link SecretRedactor} first, so a plugin that logs a bearer token does not leave it in the log file.
 *
 * <p>The logger name is the plugin id rather than the plugin's own class, which lets an operator raise or
 * lower one plugin's log level with a single logging configuration entry.
 */
public class Slf4jPluginLogger implements PluginLogger {

    private final Logger delegate;
    private final String prefix;
    private final SecretRedactor redactor;

    /**
     * @param pluginId  plugin id, used as the logger name
     * @param version   plugin version, included in the prefix
     * @param redactor  redactor for the current invocation, may be {@code null}
     */
    public Slf4jPluginLogger(String pluginId, String version, SecretRedactor redactor) {
        this.delegate = LoggerFactory.getLogger("plugin." + pluginId);
        this.prefix = "[" + pluginId + ":" + version + "] ";
        this.redactor = redactor;
    }

    @Override
    public void debug(String message, Object... args) {
        if (delegate.isDebugEnabled()) {
            delegate.debug(prefix + sanitize(message), args);
        }
    }

    @Override
    public void info(String message, Object... args) {
        delegate.info(prefix + sanitize(message), args);
    }

    @Override
    public void warn(String message, Object... args) {
        delegate.warn(prefix + sanitize(message), args);
    }

    @Override
    public void error(String message, Object... args) {
        delegate.error(prefix + sanitize(message), args);
    }

    @Override
    public void error(String message, Throwable cause) {
        delegate.error(prefix + sanitize(message), cause);
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    private String sanitize(String message) {
        return redactor == null ? message : redactor.redact(message);
    }
}
