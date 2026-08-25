package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.WorkflowFileAccess;
import com.orchpilot.workflow.sdk.context.WorkflowFileHandle;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import com.orchpilot.workflow.storage.service.WorkflowFileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The engine's {@link WorkflowFileAccess}, bound to one execution's workflow and version.
 *
 * <h2>The scope is baked in at construction</h2>
 *
 * {@code workflowId} and {@code workflowVersion} are fields, not parameters. Every call this class makes into
 * {@link WorkflowFileStorageService} passes them, and that service's repository queries key on
 * {@code (fileId, workflowId, workflowVersion)} together — so a file id belonging to another workflow simply
 * does not match. There is no check to forget, in the plugin or here.
 *
 * <h2>Errors are translated, not passed through</h2>
 *
 * A plugin compiles against the SDK and cannot see {@link FileStorageException}, so every storage failure is
 * converted to a {@link PluginException} carrying the same stable error code. The message is the storage
 * layer's own, which never contains an absolute path.
 *
 * <p>One instance per node attempt. Cheap to construct: it holds three references and no state.
 */
public class ExecutionScopedFileAccess implements WorkflowFileAccess {

    private static final Logger log = LoggerFactory.getLogger(ExecutionScopedFileAccess.class);

    private final WorkflowFileStorageService storageService;
    private final String workflowId;
    private final int workflowVersion;
    private final String pluginId;

    public ExecutionScopedFileAccess(WorkflowFileStorageService storageService,
                                     String workflowId, int workflowVersion, String pluginId) {
        this.storageService = storageService;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.pluginId = pluginId;
    }

    @Override
    public String workflowId() {
        return workflowId;
    }

    @Override
    public int workflowVersion() {
        return workflowVersion;
    }

    @Override
    public InputStream open(String fileId) {
        requireFileId(fileId);
        try {
            log.debug("Plugin {} opening file {} of workflow {} v{}", pluginId, fileId, workflowId,
                    workflowVersion);
            return storageService.getFile(workflowId, workflowVersion, fileId);
        } catch (FileStorageException ex) {
            throw translate(ex);
        }
    }

    @Override
    public Optional<WorkflowFileHandle> find(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(toHandle(storageService.getMetadata(workflowId, workflowVersion, fileId)));
        } catch (FileStorageException ex) {
            // Absence is an ordinary answer to "is there such a file", not a failure to report.
            if ("FILE_NOT_FOUND".equals(ex.getErrorCode())) {
                return Optional.empty();
            }
            throw translate(ex);
        }
    }

    @Override
    public List<WorkflowFileHandle> list() {
        try {
            List<WorkflowFileReference> references = storageService.listFiles(workflowId, workflowVersion);
            List<WorkflowFileHandle> handles = new ArrayList<>(references.size());
            for (WorkflowFileReference reference : references) {
                handles.add(toHandle(reference));
            }
            return handles;
        } catch (FileStorageException ex) {
            throw translate(ex);
        }
    }

    @Override
    public WorkflowFileHandle write(String fileName, String contentType, InputStream content) {
        if (content == null) {
            throw new PluginException("FILE_REJECTED", "No content was supplied to write.");
        }
        try {
            WorkflowFileReference stored = storageService.storeStream(workflowId, workflowVersion, fileName,
                    contentType, -1, content);
            log.info("Plugin {} stored file {} ({} bytes) on workflow {} v{}", pluginId, stored.getFileId(),
                    stored.getSize(), workflowId, workflowVersion);
            return toHandle(stored);
        } catch (FileStorageException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new PluginException("FILE_REJECTED", "No file id was supplied.");
        }
    }

    private static WorkflowFileHandle toHandle(WorkflowFileReference reference) {
        // Note what is not copied across: relativePath. A plugin never learns where the bytes live.
        return new WorkflowFileHandle(reference.getFileId(), reference.getOriginalFileName(),
                reference.getContentType(), reference.getSize(), reference.getChecksum(),
                reference.getWorkflowVersion(), reference.getCreatedAt(), reference.getCreatedBy());
    }

    /** Keeps the storage layer's stable error code so a workflow can branch on it exactly as before. */
    private static PluginException translate(FileStorageException ex) {
        return new PluginException(ex.getErrorCode(), ex.getMessage(), ex);
    }
}
