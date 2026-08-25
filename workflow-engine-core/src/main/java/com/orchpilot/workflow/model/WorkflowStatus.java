package com.orchpilot.workflow.model;

/**
 * Lifecycle state of a workflow definition.
 */
public enum WorkflowStatus {

    /** Editable, not executable. */
    DRAFT,

    /** Validated and executable. Publishing snapshots an immutable version. */
    PUBLISHED,

    /** Retired. Existing executions continue; no new ones start. */
    ARCHIVED
}
