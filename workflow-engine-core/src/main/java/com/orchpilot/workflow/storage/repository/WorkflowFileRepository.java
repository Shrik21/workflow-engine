package com.orchpilot.workflow.storage.repository;

import com.orchpilot.workflow.storage.model.FileStatus;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Access to file references.
 *
 * <h2>Every finder is scoped</h2>
 *
 * There is no {@code findByFileId} that takes only an id. Looking a file up by id alone and then checking which
 * workflow it belongs to is the classic insecure-direct-object-reference shape: it works, until one call site
 * forgets the second half. Requiring the workflow and version in the query makes the scope check structural — a
 * file id from another workflow simply does not match, so there is no check to forget.
 */
public interface WorkflowFileRepository extends MongoRepository<WorkflowFileReference, String> {

    /** The authorisation-safe lookup: an id belonging to another workflow or version returns empty. */
    Optional<WorkflowFileReference> findByFileIdAndWorkflowIdAndWorkflowVersion(
            String fileId, String workflowId, int workflowVersion);

    List<WorkflowFileReference> findByWorkflowIdAndWorkflowVersionAndStatusOrderByCreatedAtDesc(
            String workflowId, int workflowVersion, FileStatus status);

    List<WorkflowFileReference> findByWorkflowIdAndStatus(String workflowId, FileStatus status);

    List<WorkflowFileReference> findByWorkflowId(String workflowId);

    /** Supports duplicate detection within one version, which is the only scope where it is meaningful. */
    List<WorkflowFileReference> findByWorkflowIdAndWorkflowVersionAndChecksumAndStatus(
            String workflowId, int workflowVersion, String checksum, FileStatus status);

    long countByWorkflowIdAndWorkflowVersionAndStatus(String workflowId, int workflowVersion, FileStatus status);

    /** Deployment-wide count, for the health view. */
    long countByStatus(FileStatus status);
}
