package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.exception.WorkflowEngineException;

/**
 * The plugin registry could not be reached, or refused.
 *
 * <p>Deliberately distinct from a validation failure. This one means "try again later, and nothing you sent was
 * wrong", which is a different message to a user and a different decision for a caller: the catalogue falls back to
 * its cache, while an install stops and says why.
 */
public class PluginServerUnavailableException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public PluginServerUnavailableException(String message) {
        super("PLUGIN_SERVER_UNAVAILABLE", message);
    }

    public PluginServerUnavailableException(String message, Throwable cause) {
        super("PLUGIN_SERVER_UNAVAILABLE", message, cause);
    }

    /** @return the failure a caller sees when no registry is configured at all */
    public static PluginServerUnavailableException notConfigured() {
        return new PluginServerUnavailableException("""
                No plugin registry is configured, so plugins cannot be discovered or installed. Already \
                installed plugins continue to work. Set plugin.server.base-url, plugin.server.client-id and \
                plugin.server.client-secret to connect one.""");
    }
}
