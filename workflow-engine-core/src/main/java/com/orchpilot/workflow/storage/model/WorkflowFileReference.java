package com.orchpilot.workflow.storage.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The database's record of one stored file — and the <em>only</em> thing that ever names its location.
 *
 * <h2>Relative, never absolute</h2>
 *
 * {@link #relativePath} is always POSIX-style and always relative to the configured storage root
 * ({@code workflows/WF-123/v3/files/8f82c1-invoice.pdf}). No absolute path is stored here, in a workflow
 * definition, in a workflow variable, or in an export package. Three things follow from that, and all three are
 * requirements rather than conveniences:
 *
 * <ul>
 *   <li>A workflow exported from Windows imports on Linux, because nothing in it mentions {@code D:\}.</li>
 *   <li>An administrator can move the storage root without rewriting a single document.</li>
 *   <li>A reference that leaks cannot tell an attacker anything about the host filesystem.</li>
 * </ul>
 *
 * <h2>Why the reference survives its file</h2>
 *
 * Deleting is a status change plus a physical delete, not a document removal. An audit trail that loses the
 * record of what was deleted is not an audit trail, and a dangling reference with {@code status=DELETED} is a far
 * better answer to "where did that file go" than a 404.
 */
@Document(collection = "workflowFiles")
@CompoundIndex(name = "wf_files_scope",
        def = "{'tenantId': 1, 'workflowId': 1, 'workflowVersion': 1, 'status': 1}")
@CompoundIndex(name = "wf_files_checksum", def = "{'workflowId': 1, 'workflowVersion': 1, 'checksum': 1}")
public class WorkflowFileReference {

    /** Also the {@code fileId} — one identifier, so a reference cannot disagree with itself. */
    @Id
    private String fileId;

    /** Null in a single-tenant deployment. Part of every query, so one tenant cannot read another's files. */
    @Indexed
    private String tenantId;

    @Indexed
    private String workflowId;

    /**
     * The workflow version this file belongs to.
     *
     * <p>Files are scoped to a version and never shared across versions: publishing v2 leaves v1's files exactly
     * where they were, which is what stops a new release from changing what an already-running v1 execution reads.
     */
    private int workflowVersion;

    /** As the user named it, kept for display and download only — never used to build a path. */
    private String originalFileName;

    /** {@code {fileId}-{sanitised}}: collision-resistant by construction, so concurrent uploads cannot clash. */
    private String storedFileName;

    /** POSIX-style, relative to the storage root. See the class note. */
    private String relativePath;

    private String contentType;
    private long size;

    /** Lowercase hex SHA-256, computed while streaming the upload rather than by re-reading it afterwards. */
    private String checksum;

    private StorageType storageType = StorageType.LOCAL;

    private FileStatus status = FileStatus.ACTIVE;

    private Instant createdAt;
    private String createdBy;

    private Instant deletedAt;
    private String deletedBy;

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(int workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getStoredFileName() {
        return storedFileName;
    }

    public void setStoredFileName(String storedFileName) {
        this.storedFileName = storedFileName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public StorageType getStorageType() {
        return storageType;
    }

    public void setStorageType(StorageType storageType) {
        this.storageType = storageType;
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(String deletedBy) {
        this.deletedBy = deletedBy;
    }

    @Override
    public String toString() {
        return "WorkflowFileReference{fileId=" + fileId + ", workflowId=" + workflowId
                + ", version=" + workflowVersion + ", status=" + status + "}";
    }
}
