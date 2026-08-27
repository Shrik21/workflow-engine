package com.orchpilot.workflow.storage.controller;

import com.orchpilot.workflow.storage.dto.WorkflowFileDtos.ConsistencyReport;
import com.orchpilot.workflow.storage.dto.WorkflowFileDtos.FileResponse;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import com.orchpilot.workflow.storage.service.WorkflowFileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Files attached to a workflow version.
 *
 * <h2>Authorisation reuses the workflow's own access control</h2>
 *
 * There is deliberately no {@code FILE_UPLOAD} permission. A file belongs to a workflow, so the question "may
 * you touch this file" is already answered by "may you edit this workflow" — and the platform's existing
 * {@code workflowAuthorizationService} answers it, including group grants and ownership. Reading follows
 * {@code canView}, writing and deleting follow {@code canEdit}. A separate permission would let the two drift
 * apart, which is how somebody ends up able to delete files from a workflow they cannot open.
 *
 * <h2>No path ever crosses this boundary</h2>
 *
 * Every endpoint is addressed by {@code workflowId}, {@code version} and {@code fileId}. Nothing accepts a path,
 * and nothing returns one. A client holds capabilities, not locations, so there is no path for it to tamper with.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}/versions/{version}/files")
@Tag(name = "Workflow files", description = "Upload, list, download and delete files attached to a workflow version")
public class WorkflowFileController {

    private final WorkflowFileStorageService storageService;

    public WorkflowFileController(WorkflowFileStorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@workflowAuthorizationService.canEdit(authentication, #workflowId)")
    @Operation(summary = "Upload a file to a workflow version",
            description = "The file is stored under the configured root at "
                    + "workflows/{workflowId}/v{version}/files. Refused with FILE_STORAGE_NOT_CONFIGURED when no "
                    + "storage location has been set up.")
    public ResponseEntity<FileResponse> upload(@PathVariable String workflowId,
                                               @PathVariable int version,
                                               @RequestParam("file") MultipartFile file) {
        WorkflowFileReference reference = storageService.storeFile(workflowId, version, file);
        return ResponseEntity.status(201).body(FileResponse.of(reference, true));
    }

    @GetMapping
    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #workflowId)")
    @Operation(summary = "List a workflow version's files",
            description = "downloadAvailable is false when the reference exists but its content is missing from "
                    + "storage, so a broken file is visible in the list rather than only on a failed download.")
    public List<FileResponse> list(@PathVariable String workflowId, @PathVariable int version) {
        List<WorkflowFileReference> references = storageService.listFiles(workflowId, version);
        List<FileResponse> response = new ArrayList<>(references.size());
        for (WorkflowFileReference reference : references) {
            response.add(FileResponse.of(reference, storageService.exists(reference)));
        }
        return response;
    }

    @GetMapping("/{fileId}")
    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #workflowId)")
    @Operation(summary = "Download a file",
            description = "Streams the content. The file must belong to this workflow and version; an id from "
                    + "another workflow returns 404.")
    public ResponseEntity<Resource> download(@PathVariable String workflowId,
                                             @PathVariable int version,
                                             @PathVariable String fileId) {
        // Metadata first so the headers are correct before a byte is streamed, and so a missing file fails
        // before the response has been committed.
        WorkflowFileReference reference = storageService.getMetadata(workflowId, version, fileId);
        Resource body = new InputStreamResource(storageService.getFile(workflowId, version, fileId));

        // Built rather than concatenated: the builder encodes the filename, so a name containing a quote or a
        // non-ASCII character cannot break the header or smuggle a second one into it.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(reference.getOriginalFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, reference.getContentType())
                // The browser must not interpret an uploaded file as something executable in this origin.
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(reference.getSize()))
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    @DeleteMapping("/{fileId}")
    @PreAuthorize("@workflowAuthorizationService.canEdit(authentication, #workflowId)")
    @Operation(summary = "Delete a file",
            description = "Removes the content and marks the reference deleted; the audit record of the file is "
                    + "kept.")
    public ResponseEntity<Void> delete(@PathVariable String workflowId,
                                       @PathVariable int version,
                                       @PathVariable String fileId) {
        storageService.deleteFile(workflowId, version, fileId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Shares a path namespace with {@link #download}. Spring matches a literal segment before a template, so
     * this is deterministic, and a file id is sixteen hex characters and therefore can never be the literal
     * {@code consistency} — so no file is made unreachable by it.
     */
    @GetMapping("/consistency")
    @PreAuthorize("@workflowAuthorizationService.canEdit(authentication, #workflowId)")
    @Operation(summary = "Compare the database against storage",
            description = "Reports references whose content is missing and stored objects nothing references. "
                    + "Reports only; repairs nothing.")
    public ConsistencyReport consistency(@PathVariable String workflowId, @PathVariable int version) {
        return storageService.checkConsistency(workflowId);
    }
}
