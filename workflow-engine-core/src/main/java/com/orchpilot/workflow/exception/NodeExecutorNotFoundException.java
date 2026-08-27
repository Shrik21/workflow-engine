package com.orchpilot.workflow.exception;

/**
 * No executor is registered for a node type.
 *
 * <p>In practice this means a plugin that a published workflow depends on is not loaded: it was
 * deactivated, its version was deleted, or it failed to load at startup. The message names the type so
 * an operator can go straight to the plugin.
 */
public class NodeExecutorNotFoundException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    public NodeExecutorNotFoundException(String nodeType) {
        super("NODE_EXECUTOR_NOT_FOUND",
                "No executor registered for node type '" + nodeType
                        + "'. If this type comes from a plugin, check that the plugin version is ACTIVE.");
    }

    public NodeExecutorNotFoundException(String nodeType, String detail) {
        super("NODE_EXECUTOR_NOT_FOUND",
                "No executor registered for node type '" + nodeType + "': " + detail);
    }
}
