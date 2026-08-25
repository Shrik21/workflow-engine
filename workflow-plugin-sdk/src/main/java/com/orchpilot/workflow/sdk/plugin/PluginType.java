package com.orchpilot.workflow.sdk.plugin;

/**
 * Category of extension a plugin provides.
 *
 * <p>The type determines which sub-interface of {@link WorkflowPlugin} the engine expects the
 * implementation to also implement, and therefore how the plugin is wired into the runtime.
 *
 * @since 1.0.0
 */
public enum PluginType {

    /**
     * Contributes one or more executable node types to workflows.
     * Implementation must also implement {@link WorkflowNodePlugin}.
     */
    NODE,

    /**
     * Contributes named, callable actions that are not modelled as workflow nodes.
     * Implementation must also implement {@link ActionPlugin}.
     */
    ACTION,

    /**
     * Starts workflows in response to an external stimulus it listens for itself.
     * Implementation must also implement {@link TriggerPlugin}.
     */
    TRIGGER
}
