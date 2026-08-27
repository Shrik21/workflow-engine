package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.model.WorkflowVersion;

/**
 * Advances an execution through its workflow graph.
 *
 * <p>Every execution mode, synchronous, asynchronous, scheduled, event-driven and manual, calls this
 * one method. There is no per-mode execution logic anywhere in the engine: the mode determines who
 * creates the execution and on which thread this runs, and nothing else. That is what keeps a bug fixed
 * in the retry path fixed for cron runs as well as for API calls.
 *
 * <p>Implementations must be safe to call concurrently for different executions, and must refuse or
 * ignore a second concurrent call for the same execution.
 */
public interface WorkflowExecutionEngine {

    /**
     * Runs the execution until it completes, fails, parks, is paused or is cancelled.
     *
     * <p>Returns rather than throwing for workflow-level failures: a failed workflow is a normal
     * outcome recorded on the execution, not an error of the calling API. Exceptions are reserved for
     * engine-level problems such as a missing definition.
     *
     * @param execution  execution to advance; its status must be resumable
     * @param definition pinned workflow version the execution runs against
     * @param signal     data for a parked node when resuming, or {@code null}
     * @return the execution in its new state
     */
    WorkflowExecution execute(WorkflowExecution execution, WorkflowVersion definition, ResumeSignal signal);

    /**
     * Asks a locally running execution to stop at the next node boundary.
     *
     * @param executionId execution to cancel
     * @return {@code true} when the execution is running on this instance and was signalled
     */
    boolean requestCancellation(String executionId);

    /**
     * @param executionId execution id
     * @return whether this instance is currently advancing that execution
     */
    boolean isRunningLocally(String executionId);
}
