package com.orchpilot.workflow.storage.model;

/**
 * Lifecycle state of a stored file.
 *
 * <p>A deleted file keeps its reference document so the audit trail stays complete; see
 * {@link WorkflowFileReference}. {@link #ORPHANED} is set by the consistency check, never by a user action —
 * it records that the database promised a file the storage cannot produce, which is a fault to investigate
 * rather than a state to silently repair.
 */
public enum FileStatus {

    ACTIVE,

    /** Removed on request. The physical file is gone; the record of it is not. */
    DELETED,

    /** The reference exists but the physical file does not. Found by the consistency check. */
    ORPHANED
}
