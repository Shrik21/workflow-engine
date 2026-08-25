package com.orchpilot.workflow.pluginserver;

/**
 * What the marketplace says about one plugin.
 *
 * <p>The comparison of the registry's catalogue against what this engine has installed, reduced to the one word a
 * user needs. Named {@code PluginSyncStatus} rather than {@code PluginStatus} because the engine already has one of
 * those for a locally installed version, and two enums of the same name in one code base is how somebody imports
 * the wrong one and gets a compile error that says nothing useful.
 *
 * <p>Ordered by how much attention each state deserves, so a list sorted by this puts what needs looking at first.
 */
public enum PluginSyncStatus {

    /**
     * The registry has withdrawn a version this engine is running.
     *
     * <p>First because it is the only state that means something already installed may be dangerous. The engine
     * keeps running it, because pulling a plugin out from under a live workflow would be worse, and says so loudly.
     */
    REVOKED,

    /** Installed, and this engine cannot run it: wrong SDK line, wrong Java, or outside its declared range. */
    INCOMPATIBLE,

    /** Installed, and the registry offers something newer. */
    UPDATE_AVAILABLE,

    /** Installed, and the registry has superseded the whole plugin. Still runs; should not be chosen anew. */
    DEPRECATED,

    /** Offered by the registry, not installed here. */
    NOT_INSTALLED,

    /** Installed at the newest version the registry offers. Nothing to do. */
    INSTALLED,

    /**
     * Installed here and absent from the catalogue.
     *
     * <p>Either the registry no longer publishes it or this engine has never managed to sync. The distinction
     * matters to a user, so the status API reports the catalogue's age alongside.
     */
    UNKNOWN_TO_REGISTRY;

    /** @return whether this state calls for a user to do something */
    public boolean needsAttention() {
        return this == REVOKED || this == INCOMPATIBLE || this == UPDATE_AVAILABLE;
    }

    /** @return whether the plugin can be installed from this state */
    public boolean isInstallable() {
        return this == NOT_INSTALLED;
    }

    /** @return whether the plugin is present on this engine, whatever the registry thinks of it */
    public boolean isInstalled() {
        return this != NOT_INSTALLED;
    }
}
