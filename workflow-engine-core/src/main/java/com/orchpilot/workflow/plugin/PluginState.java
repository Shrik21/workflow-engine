package com.orchpilot.workflow.plugin;

/**
 * Runtime state of a loaded plugin version, as distinct from its persisted
 * {@link com.orchpilot.workflow.model.PluginStatus}.
 *
 * <p>{@link #DRAINING} is the state that makes safe unloading possible: no new executions may acquire
 * the plugin, but the ones already inside it are allowed to finish.
 */
public enum PluginState {

    /** Instantiated, {@code initialize} in progress. */
    LOADING,

    /** Fully initialised and available to executions. */
    ACTIVE,

    /** No new executions admitted; in-flight ones are finishing. */
    DRAINING,

    /** Destroyed and class loader closed. The handle must no longer be used. */
    UNLOADED,

    /** Loading or initialisation failed. */
    FAILED
}
