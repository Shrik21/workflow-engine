package com.orchpilot.workflow.forms;

/** Lifecycle of a form's editable head. */
public enum FormStatus {

    /** Editable and not yet usable by a workflow node. */
    DRAFT,

    /**
     * At least one immutable version exists and may be referenced by a workflow.
     *
     * <p>Editing a published form returns it to {@link #DRAFT} while the published version stays intact and
     * in use, mirroring how workflows behave. The two must agree, or publishing a workflow that references a
     * form would mean different things depending on which was edited last.
     */
    PUBLISHED,

    /** Retired. Existing versions keep working; no new version can be published. */
    ARCHIVED
}
