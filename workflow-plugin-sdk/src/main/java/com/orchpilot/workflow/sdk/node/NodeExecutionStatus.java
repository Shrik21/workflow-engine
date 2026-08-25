package com.orchpilot.workflow.sdk.node;

/**
 * Outcome of a single node execution attempt.
 *
 * @since 1.0.0
 */
public enum NodeExecutionStatus {

    /** The node did its work. The engine applies output mappings and follows an outgoing edge. */
    SUCCESS,

    /** The node deliberately did nothing. The engine follows the default outgoing edge. */
    SKIPPED,

    /**
     * The node cannot finish yet and the execution must be parked. The engine persists the
     * execution as {@code WAITING} and resumes it when the awaited signal arrives.
     */
    WAITING,

    /** The node failed. The engine applies the node's retry policy, then its error policy. */
    FAILED
}
