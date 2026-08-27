package com.orchpilot.workflow.storage.dto;

import com.orchpilot.workflow.storage.model.StorageType;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;

import java.time.Instant;
import java.util.List;

/**
 * What the file endpoints return.
 *
 * <h2>The one thing these deliberately omit</h2>
 *
 * No {@code relativePath}, and certainly no absolute path. A client never needs to know where a file sits — it
 * has a {@code fileId} and a download endpoint, which is a capability rather than a location. Returning the path
 * would leak the storage layout to every workflow viewer and would invite a client to construct its own paths,
 * which is precisely the pattern this module exists to prevent.
 */
public final class WorkflowFileDtos {

    private WorkflowFileDtos() {
    }

    /**
     * One file as listed or as returned from an upload.
     *
     * @param checksum          SHA-256, so a caller can verify a download without trusting the transport
     * @param downloadAvailable whether the bytes are actually present; false for a reference whose file is gone
     */
    public record FileResponse(String fileId,
                               String workflowId,
                               int workflowVersion,
                               String fileName,
                               String contentType,
                               long size,
                               String checksum,
                               StorageType storageType,
                               String uploadedBy,
                               Instant uploadedAt,
                               boolean downloadAvailable) {

        public static FileResponse of(WorkflowFileReference reference, boolean downloadAvailable) {
            return new FileResponse(reference.getFileId(), reference.getWorkflowId(),
                    reference.getWorkflowVersion(), reference.getOriginalFileName(),
                    reference.getContentType(), reference.getSize(), reference.getChecksum(),
                    reference.getStorageType(), reference.getCreatedBy(), reference.getCreatedAt(),
                    downloadAvailable);
        }
    }

    /**
     * The result of a consistency check.
     *
     * @param missingFromStorage references whose bytes are gone
     * @param orphanedInStorage  files on disk that no active reference claims
     */
    public record ConsistencyReport(String workflowId,
                                    long checked,
                                    List<String> missingFromStorage,
                                    List<String> orphanedInStorage) {
    }
}
