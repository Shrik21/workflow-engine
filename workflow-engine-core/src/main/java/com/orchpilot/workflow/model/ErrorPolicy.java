package com.orchpilot.workflow.model;

/**
 * What the engine does when a node has failed and its retry policy is exhausted.
 *
 * <p>Retry is expressed separately, by {@link RetryPolicy}, because retrying and deciding what to do
 * after retrying are different questions. {@link #RETRY} is accepted here as a convenience meaning
 * "apply the retry policy, then fail the workflow".
 */
public enum ErrorPolicy {

    /** Apply the retry policy, then fail the workflow. Equivalent to {@link #FAIL_WORKFLOW}. */
    RETRY,

    /** Mark the execution FAILED and stop. The default. */
    FAIL_WORKFLOW,

    /** Record the failure and follow the default outgoing edge as if the node were skipped. */
    SKIP,

    /**
     * Record the failure and continue along the default edge, publishing the error into the node's
     * outputs so a later decision node can branch on it.
     */
    CONTINUE,

    /**
     * Run the node's {@code compensationNodeId} to undo prior work, then fail the workflow. Use for
     * nodes with external side effects that must be reversed.
     */
    COMPENSATE
}
