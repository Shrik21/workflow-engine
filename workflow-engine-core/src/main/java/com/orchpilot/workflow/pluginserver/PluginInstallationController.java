package com.orchpilot.workflow.pluginserver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Installing plugins from the registry.
 *
 * <p>Mounted under {@code /api/plugins} beside the marketplace and the engine's own plugin endpoints. From a user's
 * point of view "what plugins does this engine have" and "install one" are the same feature, and the distinction
 * between an archive somebody uploaded and one that came from the registry is not one they should have to navigate.
 *
 * <p>Installing is guarded by {@code PLUGIN_UPLOAD} rather than a permission of its own. The risk is identical:
 * both end with third-party code running inside this JVM. Splitting them would suggest that installing from the
 * registry is the safer of the two, and the registry is a distribution channel, not a review board.
 */
@RestController
@RequestMapping("/api/plugins")
@Tag(name = "Plugins", description = "Installing, updating and removing plugins from the registry")
public class PluginInstallationController {

    private final PluginInstallationService installations;

    public PluginInstallationController(PluginInstallationService installations) {
        this.installations = installations;
    }

    /**
     * Installs the registry's latest release of a plugin.
     *
     * @param pluginId the plugin
     * @return what happened
     */
    @PostMapping("/{pluginId}/install")
    @PreAuthorize("hasAuthority('PLUGIN_UPLOAD')")
    @Operation(summary = "Install the latest release",
            description = """
                    Downloads the registry's latest release, verifies its SHA-256 against the published \
                    checksum before anything loads it, then installs and activates it.

                    The plugin is installed with no allowed hosts and no secret scopes whatever its manifest \
                    requested; grant those separately. Refused when the version cannot run on this engine, when \
                    the registry has revoked the plugin, or when the checksum does not match.""")
    public PluginInstallationService.InstallationResult install(@PathVariable String pluginId) {
        return installations.install(pluginId, null);
    }

    /**
     * Installs one specific version.
     *
     * <p>Several versions of a plugin coexist deliberately, so this adds a version rather than replacing one. A
     * workflow published against an older version keeps running it.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    @PostMapping("/{pluginId}/versions/{version}/install")
    @PreAuthorize("hasAuthority('PLUGIN_UPLOAD')")
    @Operation(summary = "Install a specific version",
            description = "Installs alongside any version already present. The default version does not move "
                    + "unless the plugin had none.")
    public PluginInstallationService.InstallationResult install(@PathVariable String pluginId,
                                                                @PathVariable String version) {
        return installations.install(pluginId, version);
    }

    /**
     * Moves to the registry's latest release.
     *
     * @param pluginId the plugin
     * @return what happened, including whether the previous version had to be kept
     */
    @PostMapping("/{pluginId}/update")
    @PreAuthorize("hasAuthority('PLUGIN_UPLOAD')")
    @Operation(summary = "Update to the latest release",
            description = """
                    Installs the newest release alongside the running one, moves the default to it, then drains \
                    and unloads the old version.

                    The old version is kept loaded when a published workflow pins it or executions are still \
                    running inside it. That is reported on the response as previousVersionRetained rather than \
                    treated as a failure: running two versions at once is a supported state.""")
    public PluginInstallationService.InstallationResult update(@PathVariable String pluginId) {
        return installations.update(pluginId);
    }

    /**
     * Loads an installed version.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    @PostMapping("/{pluginId}/versions/{version}/activate")
    @PreAuthorize("hasAuthority('PLUGIN_ACTIVATE')")
    @Operation(summary = "Activate an installed version")
    public PluginInstallationService.InstallationResult activate(@PathVariable String pluginId,
                                                                  @PathVariable String version) {
        return installations.activate(pluginId, version);
    }

    /**
     * Unloads an installed version without removing it.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    @PostMapping("/{pluginId}/versions/{version}/deactivate")
    @PreAuthorize("hasAuthority('PLUGIN_DEACTIVATE')")
    @Operation(summary = "Deactivate an installed version",
            description = "The kill switch for a misbehaving plugin. Refused while a published workflow "
                    + "depends on the version.")
    public PluginInstallationService.InstallationResult deactivate(@PathVariable String pluginId,
                                                                    @PathVariable String version) {
        return installations.deactivate(pluginId, version);
    }

    /**
     * Removes an installed version.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    @DeleteMapping("/{pluginId}/versions/{version}")
    @PreAuthorize("hasAuthority('PLUGIN_DELETE')")
    @Operation(summary = "Uninstall a version",
            description = """
                    Unloads the version, removes its archive and forgets it.

                    Refused with 409 when a published workflow still uses it, naming the workflows. Refused \
                    with 503 when executions are still running inside it, which is worth retrying in a \
                    moment.""")
    public PluginInstallationService.InstallationResult uninstall(@PathVariable String pluginId,
                                                                   @PathVariable String version) {
        return installations.uninstall(pluginId, version);
    }

    /**
     * What has been installed, updated and removed on this engine.
     *
     * @param pluginId narrows to one plugin, or absent for everything recent
     * @return the history, newest first
     */
    @GetMapping("/installation-history")
    @PreAuthorize("hasAuthority('PLUGIN_VIEW')")
    @Operation(summary = "Installation history",
            description = "Every install, update, uninstall, activation and deactivation, including the ones "
                    + "that failed or were refused.")
    public List<PluginInstallation> history(@RequestParam(required = false) String pluginId) {
        return installations.history(pluginId);
    }
}
