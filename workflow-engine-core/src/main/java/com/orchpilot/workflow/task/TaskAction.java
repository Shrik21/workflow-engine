package com.orchpilot.workflow.task;

/**
 * Something that happened to a task, recorded in its history.
 *
 * <p>Every one of these is written to {@code human_task_history} as its own document. That is more storage
 * than mutating the task in place, and it is the only way to answer the question people actually ask about an
 * approval: not "who approved it" but "who had it, for how long, and who passed it on".
 */
public enum TaskAction {

    CREATED,
    CLAIMED,
    RELEASED,
    REASSIGNED,
    DRAFT_SAVED,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    /** Held because the owning workflow instance was paused. */
    PAUSED,
    /** Returned to its pre-pause status because the instance was resumed. */
    RESUMED,
    /** Ended because the owning workflow instance was terminated. */
    TERMINATED
}
