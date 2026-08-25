package com.orchpilot.workflow.model;

import java.util.Set;

/**
 * Lifecycle state of a workflow execution.
 */
public enum ExecutionStatus {

    /** Created and persisted, not yet picked up. */
    PENDING,

    /** A node is being executed, or the next one is about to be. */
    RUNNING,

    /** Parked until an external signal arrives, typically a form submission. */
    WAITING,

    /** Paused by an operator. Resumable. */
    PAUSED,

    /** Reached an end node. Terminal. */
    COMPLETED,

    /** A node failed and the error policy ended the workflow. Terminal. */
    FAILED,

    /** Cancelled by an operator or by plugin drain policy. Terminal. */
    CANCELLED,

    /**
     * Terminated by an administrator. Terminal and permanent.
     *
     * <p>Distinct from {@link #CANCELLED} on purpose: cancellation is the engine's own housekeeping (a drain
     * policy, a withdrawn task), while termination is a deliberate administrative end-of-life carrying a reason
     * and an actor. A terminated instance can never move to {@code RUNNING}, {@code PAUSED} or {@code COMPLETED};
     * it cannot be restarted or resumed by any path.
     */
    TERMINATED;

    private static final Set<ExecutionStatus> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED, TERMINATED);
    private static final Set<ExecutionStatus> RESUMABLE = Set.of(WAITING, PAUSED, RUNNING, PENDING);

    /** @return whether no further transition is possible */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** @return whether the engine can re-enter the execution loop for this status */
    public boolean isResumable() {
        return RESUMABLE.contains(this);
    }
}
