package com.orchpilot.workflow.model;

/**
 * How an execution was started.
 *
 * <p>Recorded for attribution only. All five modes run through the same
 * {@code WorkflowExecutionEngine}; the engine's behaviour does not branch on this value.
 */
public enum ExecutionMode {

    /** Caller waits for the result on the request thread. */
    SYNCHRONOUS,

    /** Caller receives an execution id immediately; the engine runs it on a pool thread. */
    ASYNCHRONOUS,

    /** Started by the cron scheduler from a SCHEDULE trigger. */
    SCHEDULED,

    /** Started by an emitted event matching an EVENT trigger. */
    EVENT,

    /** Started explicitly by a person. */
    MANUAL
}
