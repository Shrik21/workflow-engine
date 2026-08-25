package com.orchpilot.workflow.exception;

/**
 * A validated plugin could not be staged, instantiated or initialised.
 */
public class PluginLoadException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public PluginLoadException(String coordinate, String message) {
        super("PLUGIN_LOAD_FAILED", "Failed to load plugin '" + coordinate + "': " + message);
    }

    public PluginLoadException(String coordinate, String message, Throwable cause) {
        super("PLUGIN_LOAD_FAILED", "Failed to load plugin '" + coordinate + "': " + message, cause);
    }
}
