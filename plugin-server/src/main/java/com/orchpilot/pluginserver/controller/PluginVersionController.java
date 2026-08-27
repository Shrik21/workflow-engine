package com.orchpilot.pluginserver.controller;

import com.orchpilot.pluginserver.dto.PluginResponses.PluginVersionDetail;
import com.orchpilot.pluginserver.dto.PluginResponses.PluginVersionSummary;
import com.orchpilot.pluginserver.service.PluginVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Versions of a plugin, and their lifecycle.
 *
 * <p>Every transition is its own endpoint rather than a {@code PATCH} of a status field. Publishing, deprecating
 * and revoking are different acts with different consequences and different audit meanings, and a single
 * status-setter would make the interesting one, revocation, indistinguishable in a log from a routine edit.
 */
@RestController
@RequestMapping("/api/plugins/{pluginId}")
@Tag(name = "Plugin versions", description = "Versions and their lifecycle")
public class PluginVersionController {

    private final PluginVersionService versions;

    public PluginVersionController(PluginVersionService versions) {
        this.versions = versions;
    }

    @GetMapping("/versions")
    @PreAuthorize("hasAnyAuthority('PLUGIN_VERSION_READ', 'PLUGIN_READ')")
    @Operation(summary = "List a plugin's versions, newest first")
    public List<PluginVersionSummary> list(@PathVariable String pluginId) {
        return versions.versionsOf(pluginId).stream().map(PluginVersionSummary::from).toList();
    }

    @GetMapping("/versions/{version}")
    @PreAuthorize("hasAnyAuthority('PLUGIN_VERSION_READ', 'PLUGIN_READ')")
    @Operation(summary = "Get one version",
            description = "Includes the node metadata and configuration schemas a designer renders from, and the "
                    + "checksum a downloader must reproduce.")
    public PluginVersionDetail get(@PathVariable String pluginId, @PathVariable String version) {
        return PluginVersionDetail.from(versions.require(pluginId, version));
    }

    @GetMapping("/latest")
    @PreAuthorize("hasAnyAuthority('PLUGIN_VERSION_READ', 'PLUGIN_READ')")
    @Operation(summary = "Get the version an unpinned install resolves to",
            description = """
                    The newest ACTIVE release. Pre-releases are never chosen here, so uploading 2.0.0-rc.1 cannot \
                    change what an unpinned install gets.

                    A published workflow should not use this: it pins an exact version, so that a plugin upgrade \
                    cannot change what an existing workflow does.""")
    public PluginVersionDetail latest(@PathVariable String pluginId) {
        return PluginVersionDetail.from(versions.requireLatest(pluginId));
    }

    @PostMapping("/versions/{version}/publish")
    @PreAuthorize("hasAuthority('PLUGIN_ACTIVATE')")
    @Operation(summary = "Publish a draft version, making it installable")
    public PluginVersionDetail publish(@PathVariable String pluginId, @PathVariable String version) {
        return PluginVersionDetail.from(versions.publish(pluginId, version));
    }

    @PostMapping("/versions/{version}/deactivate")
    @PreAuthorize("hasAuthority('PLUGIN_DEACTIVATE')")
    @Operation(summary = "Withdraw a version from the catalogue",
            description = "It stays downloadable by a workflow service that already knows the coordinate, so a "
                    + "pinned workflow can still be reinstalled.")
    public PluginVersionDetail deactivate(@PathVariable String pluginId, @PathVariable String version) {
        return PluginVersionDetail.from(versions.deactivate(pluginId, version));
    }

    @PostMapping("/versions/{version}/deprecate")
    @PreAuthorize("hasAuthority('PLUGIN_DEPRECATE')")
    @Operation(summary = "Mark a version superseded",
            description = "Still catalogued and still downloadable, because workflows pinned to it must keep "
                    + "working. It stops being a candidate for latest, so nothing new chooses it.")
    public PluginVersionDetail deprecate(@PathVariable String pluginId, @PathVariable String version) {
        return PluginVersionDetail.from(versions.deprecate(pluginId, version));
    }

    /**
     * Withdraws a version for cause.
     *
     * <p>Final, and the only state that refuses downloads. Reach for it when a version must not run anywhere:
     * a leaked credential, a destructive bug. Workflow services that already hold it keep their copy, so this is
     * a stop on distribution rather than a remote kill switch, and the reason is recorded so anyone who tries to
     * download it is told why.
     */
    @PostMapping("/versions/{version}/revoke")
    @PreAuthorize("hasAuthority('PLUGIN_DEPRECATE')")
    @Operation(summary = "Revoke a version, refusing all further downloads",
            description = "Irreversible. A revoked version cannot be reinstated; publish a new version instead.")
    public PluginVersionDetail revoke(@PathVariable String pluginId, @PathVariable String version,
                                     @RequestBody(required = false) RevocationRequest request) {
        String reason = request == null ? null : request.reason();
        return PluginVersionDetail.from(versions.revoke(pluginId, version, reason));
    }

    @DeleteMapping("/versions/{version}")
    @PreAuthorize("hasAuthority('PLUGIN_DELETE')")
    @Operation(summary = "Permanently delete one version and its archive",
            description = "Irreversible. Prefer revoking, which keeps the record of what existed.")
    public ResponseEntity<Void> delete(@PathVariable String pluginId, @PathVariable String version,
                                       @Parameter(description = "Must be true; a mistaken DELETE then does nothing")
                                       @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw com.orchpilot.pluginserver.exception.PluginServerException.badRequest(
                    "PLUGIN_DELETE_NOT_CONFIRMED",
                    "Deleting '" + pluginId + ":" + version + "' destroys its archive and cannot be undone. "
                            + "Repeat with confirm=true, or revoke it instead.");
        }
        versions.delete(pluginId, version);
        return ResponseEntity.noContent().build();
    }

    /**
     * Why a version is being revoked.
     *
     * @param reason shown to anybody whose download is refused afterwards
     */
    public record RevocationRequest(@Size(max = 500, message = "must be at most 500 characters") String reason) {
    }
}
