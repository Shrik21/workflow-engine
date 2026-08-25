package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.exception.PluginLoadException;
import com.orchpilot.workflow.exception.PluginNotFoundException;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.model.PluginVersion;

import java.util.Collection;

/**
 * Lifecycle owner for plugin versions.
 *
 * <p>Every operation is serialised per plugin id. Two administrators uploading the same plugin at the same
 * moment, or an unload racing a reload, would otherwise interleave a class loader close with a class load.
 *
 * <p>The persisted {@link com.orchpilot.workflow.model.PluginStatus} and the in-memory
 * {@link PluginState} are kept deliberately separate: the first survives a restart and says what should be
 * loaded, the second says what is loaded right now.
 */
public interface PluginManager {

    /**
     * Validates, stores and, unless asked otherwise, loads and activates a plugin archive.
     *
     * <p>The whole sequence: validate the archive, probe it for its identity in a throwaway class loader,
     * store the bytes in GridFS, write the metadata, load it in a real class loader, initialise it, and
     * register its node types. Nothing is registered until every step has succeeded.
     *
     * @param fileName original upload file name
     * @param content  the JAR bytes
     * @param request  operator-supplied metadata and permissions
     * @return the persisted version document
     * @throws PluginValidationException when the archive is rejected
     * @throws PluginLoadException       when a valid archive cannot be loaded
     */
    PluginVersion install(String fileName, byte[] content, PluginUploadRequest request);

    /**
     * Loads an installed version into its own class loader and registers it.
     *
     * @param pluginId plugin id
     * @param version  version to load
     * @return the loaded handle, or the existing one when it is already loaded
     * @throws PluginNotFoundException when the version is not installed
     * @throws PluginLoadException     when loading or initialisation fails
     */
    PluginHandle load(String pluginId, String version);

    /**
     * Drains and unloads a version.
     *
     * @param pluginId plugin id
     * @param version  version to unload
     * @param force    when {@code true}, unload after the grace period even if invocations are in flight
     * @return {@code true} when a loaded version was unloaded
     */
    boolean unload(String pluginId, String version, boolean force);

    /**
     * Unloads and loads a version again, picking up changed settings or permissions.
     *
     * @param pluginId plugin id
     * @param version  version to reload
     * @return the freshly loaded handle
     */
    PluginHandle reload(String pluginId, String version);

    /**
     * Changes what an installed version is allowed to reach, and applies it.
     *
     * <p>The one thing an administrator routinely needs after installing a plugin that was uploaded with the
     * wrong host list, and the only supported way to widen a plugin's reach — a plugin still cannot widen its
     * own, because this is an administrator acting, not the plugin. The change is persisted and, when the
     * version is currently loaded, the version is reloaded so the new allowlist takes effect at once rather
     * than at the next manual reload. Timeouts, the data store and everything not in {@code update} are left
     * untouched.
     *
     * @param pluginId plugin id
     * @param version  version to change
     * @param update   the new hosts, secret scopes and events flag
     * @param actor    who is performing the change
     * @return the updated version
     * @throws PluginNotFoundException when the version is not installed
     */
    PluginVersion updatePermissions(String pluginId, String version, PermissionUpdate update, String actor);

    /**
     * The permission fields an administrator may change after install.
     *
     * <p>Deliberately the three shown in the console and no more. The HTTP timeout and response ceilings are
     * engine-bounded and rarely per-plugin, and the data store is on by default; leaving them out of this
     * record is what keeps {@link #updatePermissions} from silently resetting a value it does not carry.
     *
     * @param allowedHosts  hosts the plugin may call through the engine's HTTP client, wildcards allowed
     * @param secretScopes  secret name prefixes the plugin may read
     * @param eventsEnabled whether the plugin may publish business events
     */
    record PermissionUpdate(java.util.List<String> allowedHosts, java.util.List<String> secretScopes,
                            boolean eventsEnabled) {

        public PermissionUpdate {
            allowedHosts = allowedHosts == null ? java.util.List.of() : java.util.List.copyOf(allowedHosts);
            secretScopes = secretScopes == null ? java.util.List.of() : java.util.List.copyOf(secretScopes);
        }
    }

    /**
     * Marks a version active and loads it.
     *
     * @param pluginId plugin id
     * @param version  version to activate
     * @param actor    who is performing the change
     * @return the loaded handle
     */
    PluginHandle activate(String pluginId, String version, String actor);

    /**
     * Marks a version inactive and unloads it. The kill switch for a misbehaving plugin: workflows
     * referencing it fail with a clear error instead of executing it.
     *
     * @param pluginId plugin id
     * @param version  version to deactivate
     * @param actor    who is performing the change
     */
    void deactivate(String pluginId, String version, String actor);

    /**
     * Unloads a version, removes its metadata and deletes its JAR.
     *
     * @param pluginId plugin id
     * @param version  version to delete, or {@code null} to delete every version of the plugin
     * @param actor    who is performing the change
     * @return how many versions were deleted
     */
    int delete(String pluginId, String version, String actor);

    /**
     * Chooses which version serves nodes that name the plugin without pinning a version.
     *
     * @param pluginId plugin id
     * @param version  version to make default; must be loaded
     * @param actor    who is performing the change
     */
    void setDefaultVersion(String pluginId, String version, String actor);

    /**
     * Loads every version recorded as active, at startup.
     *
     * @return number of versions successfully loaded
     */
    int loadActiveVersions();

    /** @return every currently loaded version */
    Collection<PluginHandle> loaded();
}
