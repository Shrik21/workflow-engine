package com.orchpilot.workflow.pluginserver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The marketplace, as this engine sees it.
 *
 * <p>Reads only. Installing, updating and removing plugins arrive in the next phase; this is the screen that tells
 * somebody what there is to install and what is already here.
 *
 * <p>Mounted under {@code /api/plugins} beside the engine's existing plugin endpoints rather than on a path of its
 * own, because from a user's point of view it is the same feature: what plugins does this engine have. The
 * distinction between a locally installed plugin and a registry offering is not one they should have to navigate.
 */
@RestController
@RequestMapping("/api/plugins")
@Tag(name = "Plugins", description = "The plugin marketplace: what is available, and what is installed here")
public class PluginMarketplaceController {

    private final PluginStatusService statuses;
    private final PluginCatalogSyncService sync;
    private final PluginServerProperties properties;

    public PluginMarketplaceController(PluginStatusService statuses, PluginCatalogSyncService sync,
                                       PluginServerProperties properties) {
        this.statuses = statuses;
        this.sync = sync;
        this.properties = properties;
    }

    /**
     * Every plugin either the registry or this engine knows about.
     *
     * @return the marketplace rows, most in need of attention first
     */
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('PLUGIN_VIEW')")
    @Operation(summary = "Plugin status",
            description = """
                    Compares the registry's catalogue against what this engine has installed and reports one status \
                    per plugin: NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, INCOMPATIBLE, DEPRECATED, REVOKED, or \
                    UNKNOWN_TO_REGISTRY for something installed here that the catalogue does not mention.

                    Served entirely from the cached catalogue, so it answers while the registry is unreachable. \
                    Check the catalogue's age with /api/plugins/catalog-health before treating an absent plugin as \
                    proof it does not exist.""")
    public List<PluginStatusService.PluginStatusView> status() {
        return statuses.statuses();
    }

    /**
     * One plugin's status.
     *
     * @param pluginId the plugin
     * @return its row, or 404 when neither side knows it
     */
    @GetMapping("/status/{pluginId}")
    @PreAuthorize("hasAuthority('PLUGIN_VIEW')")
    @Operation(summary = "One plugin's status")
    public ResponseEntity<PluginStatusService.PluginStatusView> status(@PathVariable String pluginId) {
        return statuses.status(pluginId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * How current the cached catalogue is.
     *
     * <p>Separate from the statuses so a screen can show "last synchronised 40 minutes ago" without a second
     * interpretation of the same payload, and so a monitoring check has something small to poll.
     *
     * @return the catalogue's health
     */
    @GetMapping("/catalog-health")
    @PreAuthorize("hasAuthority('PLUGIN_VIEW')")
    @Operation(summary = "Catalogue sync health",
            description = "When the catalogue was last confirmed current, whether it is stale, and why the last "
                    + "attempt failed. A stale catalogue is not an error: installed plugins keep working.")
    public PluginStatusService.CatalogHealth catalogHealth() {
        return statuses.catalogHealth(properties);
    }

    /**
     * Refreshes the catalogue now.
     *
     * <p>Takes {@code PLUGIN_VIEW} rather than an administrative permission: it reads another service and writes
     * nothing but a cache, and making it privileged would mean a user staring at a stale marketplace has to find an
     * administrator to press a refresh button.
     *
     * @return what the sync did
     */
    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('PLUGIN_VIEW')")
    @Operation(summary = "Sync the catalogue from the registry",
            description = "Fetches the catalogue conditionally, so an unchanged one costs a 304. Answers with "
                    + "UPDATED, UNCHANGED, FAILED or NOT_CONFIGURED; a failure keeps the previous catalogue.")
    public PluginCatalogSyncService.SyncResult sync() {
        return sync.sync();
    }

    /**
     * Where this engine points, and whether it can be reached.
     *
     * @return the registry's address and reachability, never the client secret
     */
    @GetMapping("/registry")
    @PreAuthorize("hasAuthority('PLUGIN_VIEW')")
    @Operation(summary = "Which registry this engine uses")
    public Map<String, Object> registry() {
        return Map.of(
                "configured", properties.isConfigured(),
                "description", properties.describe(),
                "syncIntervalSeconds", properties.getSyncInterval().toSeconds());
    }
}
