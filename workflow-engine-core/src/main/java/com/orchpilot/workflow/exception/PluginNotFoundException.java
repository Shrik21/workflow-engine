package com.orchpilot.workflow.exception;

/**
 * The requested plugin or plugin version is not installed.
 */
public class PluginNotFoundException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public PluginNotFoundException(String pluginId) {
        super("PLUGIN_NOT_FOUND", "No plugin with id '" + pluginId + "'");
    }

    public PluginNotFoundException(String pluginId, String version) {
        super("PLUGIN_VERSION_NOT_FOUND", "Plugin '" + pluginId + "' has no version '" + version + "'");
    }
}
