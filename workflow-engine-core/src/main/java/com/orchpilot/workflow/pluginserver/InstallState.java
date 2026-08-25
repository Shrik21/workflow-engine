package com.orchpilot.workflow.pluginserver;

/**
 * Where one installed version is in its local lifecycle.
 *
 * <p>Local to this engine, and unrelated to the version's state in the registry. A version can be ACTIVE here and
 * DEPRECATED there, which is the ordinary situation for a workflow pinned to an older release: the registry has
 * moved on and this engine is still running it, correctly.
 */
public enum InstallState {

    /** Being fetched from the registry. */
    DOWNLOADING,

    /** Downloaded; checksum being verified. */
    VALIDATING,

    /** Verified and cached, not currently loaded into a class loader. */
    INSTALLED,

    /** Loaded, its node types registered, ready to execute. */
    ACTIVE,

    /** Deliberately unloaded. The kill switch for a plugin that is misbehaving. */
    DISABLED,

    /**
     * Draining before an unload, and refusing new executions.
     *
     * <p>The state that makes an update safe: the version stays loaded so executions already inside it finish,
     * while nothing new is admitted.
     */
    STOPPING,

    /** Install or load failed. Nothing was registered, and the reason is on the record. */
    INSTALL_FAILED;

    /** @return whether this version can serve a node execution */
    public boolean isRunnable() {
        return this == ACTIVE;
    }

    /** @return whether this state is a step in an install rather than a resting place */
    public boolean isTransient() {
        return this == DOWNLOADING || this == VALIDATING || this == STOPPING;
    }
}
