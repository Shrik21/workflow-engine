package com.orchpilot.workflow.task;

/**
 * Where a human task is in its life.
 *
 * <p>Deliberately small. Every additional state is a transition that has to be authorised, audited and
 * rendered, and a task really only has three interesting conditions: nobody has taken it, somebody has, and
 * it is over.
 *
 * <p>{@link #OPEN} and {@link #ASSIGNED} are both actionable; the difference is whether a specific person is
 * accountable. A task offered to a group starts OPEN, and claiming it moves it to ASSIGNED with that person
 * recorded. A task addressed to one person is created ASSIGNED, because asking them to claim what was already
 * theirs is ceremony.
 */
public enum TaskStatus {

    /** Offered to candidates, claimed by nobody. */
    OPEN,

    /** One person is responsible for it. */
    ASSIGNED,

    /**
     * Held because the owning workflow instance was paused.
     *
     * <p>Not terminal and not actionable: the assignee may still open the form and save a draft, but cannot
     * submit until the instance is resumed, at which point the task returns to the status it held before
     * (recorded in {@code previousStatus}). Only an active task — {@link #OPEN} or {@link #ASSIGNED} — is paused;
     * a {@link #COMPLETED} one is left alone.
     */
    PAUSED,

    /** Submitted. The execution has been resumed. */
    COMPLETED,

    /** Withdrawn by an administrator, or by the execution being cancelled. */
    CANCELLED,

    /** Passed its hard expiry without being submitted. */
    EXPIRED,

    /**
     * Ended because the owning workflow instance was terminated. Terminal and permanent.
     *
     * <p>Distinct from {@link #CANCELLED} for the same reason the instance status is: it records a deliberate
     * administrative termination. The assignee may still open the form and save a draft — a terminated task is
     * not deleted — but can never submit it.
     */
    TERMINATED;

    /** @return whether no further action is possible */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == EXPIRED || this == TERMINATED;
    }

    /** @return whether somebody can still submit this task (it is open to work and not held) */
    public boolean isActionable() {
        return this == OPEN || this == ASSIGNED;
    }

    /**
     * @return whether the assignee may still save a draft
     *
     * <p>Draft-saving is deliberately broader than submitting: it stays available while an instance is paused or
     * terminated, so a person never loses form input to an administrative action they did not cause. Only the
     * genuinely finished states — submitted, withdrawn, expired — close the door on a draft.
     */
    public boolean allowsDraft() {
        return this == OPEN || this == ASSIGNED || this == PAUSED || this == TERMINATED;
    }
}
