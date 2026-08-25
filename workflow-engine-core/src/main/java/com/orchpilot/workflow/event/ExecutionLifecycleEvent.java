package com.orchpilot.workflow.event;

import com.orchpilot.workflow.model.ExecutionStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Published when an execution changes to a state worth observing: started, parked, completed, failed or
 * cancelled.
 *
 * <p>Consumers use this for notifications, metrics and downstream orchestration. It carries the result
 * rather than the whole variable snapshot, because the variables of a large execution are not something
 * every listener should be handed.
 *
 * @param executionId     execution id
 * @param workflowId      workflow id
 * @param workflowVersion pinned workflow version
 * @param status          the new status
 * @param nodeId          node that caused the transition, may be {@code null}
 * @param output          execution result for terminal statuses, otherwise empty
 * @param errorCode       failure code when the status is FAILED
 * @param at              transition time
 */
public record ExecutionLifecycleEvent(String executionId, String workflowId, int workflowVersion,
                                      ExecutionStatus status, String nodeId, Map<String, Object> output,
                                      String errorCode, Instant at) {

    /**
     * @param executionId     execution id
     * @param workflowId      workflow id
     * @param workflowVersion pinned version
     * @param status          new status
     * @param nodeId          node that caused the transition
     * @return an event with no result payload, stamped now
     */
    public static ExecutionLifecycleEvent of(String executionId, String workflowId, int workflowVersion,
                                             ExecutionStatus status, String nodeId) {
        return new ExecutionLifecycleEvent(executionId, workflowId, workflowVersion, status, nodeId,
                Map.of(), null, Instant.now());
    }
}
