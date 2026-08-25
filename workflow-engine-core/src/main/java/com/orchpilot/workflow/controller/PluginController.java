package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.dto.PluginExecutionResponse;
import com.orchpilot.workflow.dto.PluginResponse;
import com.orchpilot.workflow.dto.PluginVersionResponse;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.plugin.PluginManager;
import com.orchpilot.workflow.plugin.PluginUploadRequest;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.json.JsonException;
import com.orchpilot.workflow.service.PluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin installation and lifecycle endpoints.
 *
 * <p>Everything here installs or controls executable code, so the whole path sits behind the administrative API key.
 * See the security section of the README for why class loader isolation is not a substitute for that.
 *
 * <p>Upload accepts two shapes so it is usable from both a UI and a shell: a {@code metadata} part containing JSON,
 * or plain form fields. Requiring a JSON part with the right content type is a common source of confusing 400s from
 * curl, so the form fields exist as the simpler path and take precedence when both are supplied.
 */
@RestController
@RequestMapping("/api/plugins")
@Tag(name = "Plugins", description = "Install, activate and inspect runtime plugins")
@SecurityRequirement(name = "adminApiKey")
public class PluginController {

    private static final Logger log = LoggerFactory.getLogger(PluginController.class);

    private final PluginManager pluginManager;
    private final PluginService pluginService;

    public PluginController(PluginManager pluginManager, PluginService pluginService) {
        this.pluginManager = pluginManager;
        this.pluginService = pluginService;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload and install a plugin JAR",
            description = "Validates the archive, stores it in GridFS, loads it in an isolated class loader and "
                    + "registers its node types. The engine is not restarted and the new node types are available "
                    + "from GET /api/nodes immediately.")
    public ResponseEntity<PluginVersionResponse> upload(
            @Parameter(description = "The plugin JAR")
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Optional JSON with pluginId, version, mainClass, description, allowedHosts, "
                    + "secretScopes, settings, dependencies, expectedSha256, activate and eventsEnabled")
            @RequestPart(value = "metadata", required = false) String metadata,
            @RequestParam(required = false) String pluginId,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String mainClass,
            @RequestParam(required = false) String description,
            @Parameter(description = "Comma-separated hosts the plugin may call, e.g. api.sendgrid.com")
            @RequestParam(required = false) String allowedHosts,
            @Parameter(description = "Comma-separated secret name prefixes the plugin may read, e.g. sendgrid.")
            @RequestParam(required = false) String secretScopes,
            @RequestParam(required = false) String expectedSha256,
            @RequestParam(defaultValue = "true") boolean activate,
            @RequestParam(defaultValue = "true") boolean eventsEnabled,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {

        if (file == null || file.isEmpty()) {
            throw new PluginValidationException("No file was uploaded under the part name 'file'");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new PluginValidationException("Could not read the uploaded file: " + ex.getMessage());
        }

        String actor = ActorResolver.resolve(actorHeader);
        PluginUploadRequest request = buildRequest(metadata, pluginId, version, mainClass, description,
                allowedHosts, secretScopes, expectedSha256, activate, eventsEnabled, actor);
        log.info("Plugin upload received from {}: {} ({} bytes)", actor, file.getOriginalFilename(),
                content.length);

        var installed = pluginManager.install(file.getOriginalFilename(), content, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(pluginService.version(installed.getPluginId(), installed.getVersion()));
    }

    @GetMapping
    @Operation(summary = "List installed plugins")
    public List<PluginResponse> list() {
        return pluginService.list();
    }

    @GetMapping("/{pluginId}")
    @Operation(summary = "Get a plugin and its versions")
    public PluginResponse get(@PathVariable String pluginId) {
        return pluginService.get(pluginId);
    }

    @GetMapping("/{pluginId}/versions")
    @Operation(summary = "List a plugin's versions",
            description = "Several versions can be installed and loaded at once so that workflows pinning an older "
                    + "version keep working after an upgrade.")
    public List<PluginVersionResponse> versions(@PathVariable String pluginId) {
        return pluginService.versions(pluginId);
    }

    @GetMapping("/{pluginId}/executions")
    @Operation(summary = "List recorded invocations of a plugin",
            description = "Request and response payloads are the redacted, truncated copies the engine persisted.")
    public Page<PluginExecutionResponse> executions(
            @PathVariable String pluginId,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pluginService.executions(pluginId, version,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
    }

    @PostMapping("/{pluginId}/activate")
    @Operation(summary = "Activate a plugin version",
            description = "Marks the version active and loads it. Its node types become resolvable immediately.")
    public PluginVersionResponse activate(
            @PathVariable String pluginId,
            @RequestParam String version,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        pluginManager.activate(pluginId, version, ActorResolver.resolve(actorHeader));
        return pluginService.version(pluginId, version);
    }

    @PostMapping("/{pluginId}/deactivate")
    @Operation(summary = "Deactivate a plugin version",
            description = "The kill switch. New executions stop being admitted, in-flight ones are drained, and the "
                    + "class loader is closed. Workflows referencing it then fail with a clear error rather than "
                    + "executing it.")
    public PluginVersionResponse deactivate(
            @PathVariable String pluginId,
            @RequestParam String version,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        pluginManager.deactivate(pluginId, version, ActorResolver.resolve(actorHeader));
        return pluginService.version(pluginId, version);
    }

    @PostMapping("/{pluginId}/reload")
    @Operation(summary = "Reload a plugin version",
            description = "Drains, unloads and loads the version again, picking up changed settings or permissions. "
                    + "Executions that already started keep the version they began on.")
    public PluginVersionResponse reload(
            @PathVariable String pluginId,
            @RequestParam String version,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        pluginManager.reload(pluginId, version);
        log.info("Plugin {}:{} reloaded by {}", pluginId, version, ActorResolver.resolve(actorHeader));
        return pluginService.version(pluginId, version);
    }

    @PutMapping("/{pluginId}/permissions")
    @Operation(summary = "Change what an installed version may reach",
            description = "Sets the allowed hosts, secret scopes and events flag for a version, and reloads it "
                    + "if it is loaded so the change takes effect at once. The way to grant a plugin a host it "
                    + "was not uploaded with, without reinstalling. Requires the same permission as an upload, "
                    + "because it widens the plugin's reach.")
    public PluginVersionResponse updatePermissions(
            @PathVariable String pluginId,
            @RequestParam String version,
            @RequestBody PermissionUpdateRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        pluginManager.updatePermissions(pluginId, version,
                new PluginManager.PermissionUpdate(request.allowedHosts(), request.secretScopes(),
                        request.eventsEnabled()),
                ActorResolver.resolve(actorHeader));
        return pluginService.version(pluginId, version);
    }

    @PostMapping("/{pluginId}/default-version")
    @Operation(summary = "Choose which version serves unpinned nodes")
    public PluginResponse setDefaultVersion(
            @PathVariable String pluginId,
            @RequestParam String version,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        pluginManager.setDefaultVersion(pluginId, version, ActorResolver.resolve(actorHeader));
        return pluginService.get(pluginId);
    }

    @DeleteMapping("/{pluginId}")
    @Operation(summary = "Delete a plugin version, or every version",
            description = "Unloads the version, removes its metadata and deletes its JAR from GridFS. Omit version "
                    + "to remove the plugin entirely.")
    public DeleteResponse delete(
            @PathVariable String pluginId,
            @RequestParam(required = false) String version,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        int deleted = pluginManager.delete(pluginId, version, ActorResolver.resolve(actorHeader));
        return new DeleteResponse(pluginId, version, deleted);
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private PluginUploadRequest buildRequest(String metadataJson, String pluginId, String version,
                                             String mainClass, String description, String allowedHosts,
                                             String secretScopes, String expectedSha256, boolean activate,
                                             boolean eventsEnabled, String actor) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                metadata = Json.parseObject(metadataJson);
            } catch (JsonException ex) {
                throw new PluginValidationException("The metadata part is not valid JSON: " + ex.getMessage());
            }
        }

        // Form fields win over the JSON part: they are the simpler path and an explicit query parameter should
        // not be silently ignored because a stale JSON body disagreed with it.
        String effectivePluginId = firstNonBlank(pluginId, asText(metadata.get("pluginId")));
        String effectiveVersion = firstNonBlank(version, asText(metadata.get("version")));
        String effectiveMainClass = firstNonBlank(mainClass, asText(metadata.get("mainClass")));
        String effectiveDescription = firstNonBlank(description, asText(metadata.get("description")));
        String effectiveChecksum = firstNonBlank(expectedSha256, asText(metadata.get("expectedSha256")));

        List<String> hosts = mergeList(allowedHosts, metadata.get("allowedHosts"));
        List<String> scopes = mergeList(secretScopes, metadata.get("secretScopes"));
        List<String> dependencies = mergeList(null, metadata.get("dependencies"));
        Map<String, Object> settings = metadata.get("settings") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) metadata.get("settings"))
                : new LinkedHashMap<>();
        boolean effectiveActivate = metadata.containsKey("activate")
                ? Boolean.parseBoolean(String.valueOf(metadata.get("activate"))) : activate;
        boolean effectiveEvents = metadata.containsKey("eventsEnabled")
                ? Boolean.parseBoolean(String.valueOf(metadata.get("eventsEnabled"))) : eventsEnabled;

        return new PluginUploadRequest(effectivePluginId, effectiveVersion, effectiveMainClass,
                effectiveDescription, hosts, scopes, settings, dependencies, effectiveChecksum,
                effectiveActivate, effectiveEvents, actor);
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    /**
     * Accepts a comma-separated form field or a JSON array, so both upload shapes produce the same list.
     */
    private static List<String> mergeList(String commaSeparated, Object jsonValue) {
        List<String> values = new ArrayList<>();
        if (commaSeparated != null && !commaSeparated.isBlank()) {
            for (String part : commaSeparated.split(",")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        if (values.isEmpty() && jsonValue instanceof List) {
            for (Object item : (List<?>) jsonValue) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
        }
        return values;
    }

    /**
     * Result of a delete.
     *
     * @param pluginId plugin id
     * @param version  version deleted, or {@code null} when the whole plugin was removed
     * @param deleted  how many versions were removed
     */
    public record DeleteResponse(String pluginId, String version, int deleted) {
    }

    /**
     * A change to a version's permissions.
     *
     * <p>The whole set is sent, not a delta: the console shows the current lists and submits the edited ones,
     * so an omitted field means "make it empty", which for {@code allowedHosts} is the deny-all it looks like.
     * A partial-update endpoint would make "remove the last host" indistinguishable from "leave hosts alone".
     *
     * @param allowedHosts  hosts the plugin may call, wildcards such as {@code *.sendgrid.com} allowed
     * @param secretScopes  secret name prefixes the plugin may read
     * @param eventsEnabled whether the plugin may publish business events
     */
    public record PermissionUpdateRequest(List<String> allowedHosts, List<String> secretScopes,
                                          boolean eventsEnabled) {
    }
}
