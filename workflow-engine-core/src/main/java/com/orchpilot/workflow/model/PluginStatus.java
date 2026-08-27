package com.orchpilot.workflow.model;

/**
 * Lifecycle state of a plugin version.
 *
 * <p>{@link #INACTIVE} is the kill switch: an operator can stop a misbehaving plugin from being used
 * by new executions without deleting it, and without touching the workflows that reference it.
 */
public enum PluginStatus {

    /** Stored and validated, not loaded into a class loader. */
    INSTALLED,

    /** Loaded, registered, and available to workflows. */
    ACTIVE,

    /** Stored but unloaded. Node types are unresolvable; workflows referencing it fail validation. */
    INACTIVE,

    /** Loading or initialisation failed. See {@code loadError} on the version document. */
    FAILED,

    /** Soft-deleted. Retained for audit; not loadable. */
    DELETED
}
