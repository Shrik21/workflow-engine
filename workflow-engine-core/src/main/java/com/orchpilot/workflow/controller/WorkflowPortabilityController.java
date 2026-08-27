package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.portability.WorkflowExportService;
import com.orchpilot.workflow.portability.WorkflowImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Export a workflow to an encrypted {@code .orchpilot} file, and import one back.
 *
 * <h2>Why a separate controller</h2>
 *
 * These three endpoints share a concern the rest of {@link WorkflowController} does not: they move an
 * encrypted binary in and out over multipart, and one of them ingests an <em>untrusted</em> file. Keeping them
 * apart makes the trust boundary obvious — everything here treats its input as hostile until the authenticated
 * decrypt in {@link WorkflowImportService} proves otherwise — and keeps the password, which must never be
 * logged or echoed, confined to this file's request DTOs and wiped after use.
 */
@RestController
@RequestMapping("/api/workflows")
@Tag(name = "Workflow portability", description = "Export and import workflows as encrypted .orchpilot files")
public class WorkflowPortabilityController {

    private final WorkflowExportService exportService;
    private final WorkflowImportService importService;

    public WorkflowPortabilityController(WorkflowExportService exportService,
                                         WorkflowImportService importService) {
        this.exportService = exportService;
        this.importService = importService;
    }

    /**
     * What to export and how to protect it.
     *
     * @param includeForms      package referenced form definitions
     * @param includeVariables  package workflow variables
     * @param includePluginDependencies package the plugin id/version list
     * @param includePermissions package the access groups
     * @param encryptionMode    {@code PLATFORM} (envelope-encrypted under the engine master key) or
     *                          {@code PASSWORD} (Argon2id-derived from {@code password})
     * @param password          the export password, required only for {@code PASSWORD} mode
     */
    public record ExportRequest(Boolean includeForms, Boolean includeVariables,
                                Boolean includePluginDependencies, Boolean includePermissions,
                                String encryptionMode, String password) {
    }

    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #id)")
    @PostMapping("/{id}/export")
    @Operation(summary = "Export a workflow to an encrypted .orchpilot file",
            description = "Never includes secrets — only references to them. Platform mode encrypts under the "
                    + "engine master key; password mode derives the key from a password with Argon2id and stores "
                    + "neither the password nor the key.")
    public ResponseEntity<byte[]> export(
            @PathVariable String id,
            @RequestBody(required = false) ExportRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        ExportRequest safe = request == null
                ? new ExportRequest(true, true, true, true, "PLATFORM", null) : request;
        char[] password = safe.password() == null ? null : safe.password().toCharArray();
        try {
            WorkflowExportService.ExportOptions options = new WorkflowExportService.ExportOptions(
                    orDefault(safe.includeForms(), true), orDefault(safe.includeVariables(), true),
                    orDefault(safe.includePluginDependencies(), true),
                    orDefault(safe.includePermissions(), true),
                    safe.encryptionMode() == null ? "PLATFORM" : safe.encryptionMode(), password);
            WorkflowExportService.ExportResult result = exportService.export(id, options,
                    ActorResolver.resolve(actorHeader));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + result.fileName() + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(result.bytes());
        } finally {
            wipe(password);
        }
    }

    @PostMapping(value = "/import/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Validate an .orchpilot file without importing",
            description = "Decrypts and checks the file, returning a preview: the workflow, its plugin "
                    + "dependencies and any that are missing or out of date, the credential references that must "
                    + "be mapped, the access groups, and any id conflict. Nothing is written.")
    public WorkflowImportService.ValidationResult validate(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        char[] pw = password == null ? null : password.toCharArray();
        try {
            return importService.validate(bytes(file), pw, ActorResolver.resolve(actorHeader));
        } finally {
            wipe(pw);
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a workflow from an .orchpilot file",
            description = "Creates a new workflow — with new ids, and its forms re-created with new ids — in the "
                    + "caller's own tenant. The tenant recorded in the file is provenance only and is ignored. An "
                    + "existing workflow is never overwritten.")
    public WorkflowImportService.ImportResult importWorkflow(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", required = false) String password,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        char[] pw = password == null ? null : password.toCharArray();
        try {
            return importService.importWorkflow(bytes(file), pw, ActorResolver.resolve(actorHeader));
        } finally {
            wipe(pw);
        }
    }

    private static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the uploaded file.", ex);
        }
    }

    private static boolean orDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            java.util.Arrays.fill(value, '\0');
        }
    }
}
