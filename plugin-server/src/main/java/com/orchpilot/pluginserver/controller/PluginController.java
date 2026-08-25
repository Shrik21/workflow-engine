package com.orchpilot.pluginserver.controller;

import com.orchpilot.pluginserver.dto.PluginResponses.PluginSummary;
import com.orchpilot.pluginserver.dto.PluginResponses.PluginUploadResult;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.service.PluginService;
import com.orchpilot.pluginserver.service.PluginVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Plugins: uploading them, listing them, and their availability.
 *
 * <p>Version-level operations are in {@link PluginVersionController} and downloads in
 * {@link PluginDownloadController}, which keeps each file's authorization story short enough to review.
 */
@RestController
@RequestMapping("/api/plugins")
@Tag(name = "Plugins", description = "Upload and manage plugins")
public class PluginController {

    private final PluginService plugins;
    private final PluginVersionService versions;

    public PluginController(PluginService plugins, PluginVersionService versions) {
        this.plugins = plugins;
        this.versions = versions;
    }

    /**
     * Uploads an archive.
     *
     * <p>Identity comes from the archive, not from the request. The plugin id, version, main class and node types
     * are read from {@code META-INF/workflow-plugin.json} inside the JAR, so what the registry records is what the
     * artefact says about itself. Accepting them as form fields would let the two disagree, and the record would
     * be the one nobody can verify.
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PLUGIN_UPLOAD')")
    @Operation(summary = "Upload a plugin archive",
            description = """
                    Validates the archive without executing any of it, stores the bytes in GridFS and records the \
                    version.

                    Identity is read from META-INF/workflow-plugin.json inside the JAR rather than taken from \
                    form fields, so the registry's record and the artefact cannot disagree.

                    Answers 422 listing every problem when the archive or its manifest is unacceptable, and 409 \
                    when that plugin version already exists: published versions are immutable.""")
    @ApiResponse(responseCode = "201", description = "Stored")
    @ApiResponse(responseCode = "409", description = "That version already exists")
    @ApiResponse(responseCode = "422", description = "The archive or manifest was rejected")
    public ResponseEntity<PluginUploadResult> upload(@RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw PluginServerException.badRequest("PLUGIN_ARCHIVE_MISSING_PART",
                    "Send the archive as a multipart part named 'file'.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw PluginServerException.badRequest("PLUGIN_ARCHIVE_UNREADABLE",
                    "The uploaded archive could not be read: " + ex.getMessage());
        }

        var stored = versions.upload(file.getOriginalFilename(), content);
        return ResponseEntity.status(HttpStatus.CREATED).body(PluginUploadResult.from(stored));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PLUGIN_READ')")
    @Operation(summary = "List plugins")
    public Page<PluginSummary> list(@RequestParam(required = false) String search,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Direction.ASC, "_id"));
        return plugins.list(search, pageable).map(PluginSummary::from);
    }

    @GetMapping("/{pluginId}")
    @PreAuthorize("hasAuthority('PLUGIN_READ')")
    @Operation(summary = "Get one plugin")
    public PluginSummary get(@PathVariable String pluginId) {
        return PluginSummary.from(plugins.require(pluginId));
    }

    @PostMapping("/{pluginId}/activate")
    @PreAuthorize("hasAuthority('PLUGIN_ACTIVATE')")
    @Operation(summary = "Make a plugin available again")
    public PluginSummary activate(@PathVariable String pluginId) {
        return PluginSummary.from(plugins.activate(pluginId));
    }

    @PostMapping("/{pluginId}/deactivate")
    @PreAuthorize("hasAuthority('PLUGIN_DEACTIVATE')")
    @Operation(summary = "Withdraw a plugin from the catalogue",
            description = "Stops new installations. A workflow service that already installed a version keeps "
                    + "running it, which is the difference between this and revoking.")
    public PluginSummary deactivate(@PathVariable String pluginId) {
        return PluginSummary.from(plugins.deactivate(pluginId));
    }

    /**
     * Removes a plugin, every version of it, and their archives.
     *
     * <p>The only destructive operation in the registry, which is why it takes its own permission and requires an
     * explicit confirmation. Everything else is a state change, because these artefacts are running elsewhere.
     */
    @DeleteMapping("/{pluginId}")
    @PreAuthorize("hasAuthority('PLUGIN_DELETE')")
    @Operation(summary = "Permanently delete a plugin and all its versions",
            description = """
                    Irreversible, and unlike deactivating it destroys the archives. A workflow service that has \
                    already installed a version keeps its local copy, but nothing can install it again and the \
                    record of what it was is gone.

                    Requires confirm=true, so a DELETE issued by mistake against the wrong id does nothing.""")
    @ApiResponse(responseCode = "200", description = "Deleted")
    public Map<String, Object> delete(@PathVariable String pluginId,
                                      @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw PluginServerException.badRequest("PLUGIN_DELETE_NOT_CONFIRMED",
                    "Deleting '" + pluginId + "' destroys every version and archive and cannot be undone. "
                            + "Repeat the request with confirm=true, or deactivate it instead.");
        }
        int removed = versions.deletePlugin(pluginId);
        return Map.of("pluginId", pluginId, "versionsRemoved", removed);
    }
}
