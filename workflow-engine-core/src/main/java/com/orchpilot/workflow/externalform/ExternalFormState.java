package com.orchpilot.workflow.externalform;

/**
 * The state the public page renders for a link, computed entirely server-side from the token, the task and the
 * workflow instance.
 *
 * <p>Deliberately a small, customer-facing vocabulary: it never names a node, a workflow or an internal id, only
 * what the person in front of the form needs to know — whether they can fill it in, only save a draft, or
 * nothing at all.
 */
public enum ExternalFormState {

    /** Open for editing, drafting and submitting. */
    OPEN,

    /** The instance is paused: editable and draftable, but not submittable. */
    WORKFLOW_PAUSED,

    /** The instance is terminated: editable and draftable, but never submittable. */
    WORKFLOW_TERMINATED,

    /** Already submitted (task completed, or token used). */
    ALREADY_SUBMITTED,

    /** The link has expired. */
    EXPIRED,

    /** The link was revoked by the organization. */
    REVOKED,

    /** The task was cancelled. */
    CANCELLED,

    /** The link is not valid. */
    INVALID
}
