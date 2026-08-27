package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.model.NodeExecutionRecord;
import com.orchpilot.workflow.model.PendingSignal;
import com.orchpilot.workflow.model.WorkflowExecution;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * An execution as returned by the API.
 *
 * <p>{@code variables} is included because operators debugging a stuck run need it, and the engine has
 * already redacted known secret values before persisting anything. {@code pendingSignal} tells a client what
 * a {@code WAITING} execution is waiting for, which is what a task inbox renders.
 *
 * @param executionId     execution id
 * @param workflowId      workflow id
 * @param workflowVersion pinned version
 * @param workflowName    workflow name
 * @param status          current status
 * @param mode            how it was started
 * @param currentNodeId   node it is on, parked at, or failed at
 * @param output          result, once completed
 * @param variables       full scoped variable snapshot
 * @param nodeHistory     per-node attempt history
 * @param pendingSignal   what a waiting execution is waiting for
 * @param error           failure detail
 * @param stepCount       nodes executed
 * @param startedAt       start time
 * @param completedAt     completion time
 * @param updatedAt       last state change
 * @param correlationId   caller-supplied correlation id
 */
public record ExecutionResponse(String executionId, String workflowId, int workflowVersion,
                                String workflowName, String status, String mode, String currentNodeId,
                                String currentNodeType, Instant currentNodeStartedAt,
                                Map<String, Object> output, Map<String, Object> variables,
                                List<NodeHistoryView> nodeHistory, PendingSignalView pendingSignal,
                                ErrorView error, int stepCount, Instant startedAt, Instant completedAt,
                                Instant updatedAt, String correlationId) {

    /**
     * @param execution persistence model
     * @return the full API representation
     */
    public static ExecutionResponse from(WorkflowExecution execution) {
        return new ExecutionResponse(
                execution.getId(),
                execution.getWorkflowId(),
                execution.getWorkflowVersion(),
                execution.getWorkflowName(),
                String.valueOf(execution.getStatus()),
                String.valueOf(execution.getMode()),
                execution.getCurrentNodeId(),
                execution.getCurrentNodeType(),
                execution.getCurrentNodeStartedAt(),
                execution.getOutput(),
                execution.getVariables(),
                execution.getNodeHistory().stream().map(NodeHistoryView::from).toList(),
                PendingSignalView.from(execution.getPendingSignal()),
                ErrorView.from(execution),
                execution.getStepCount(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getUpdatedAt(),
                execution.getCorrelationId());
    }

    /**
     * Compact form for the asynchronous start response, where the caller only needs to know what to poll.
     *
     * @param execution persistence model
     * @return an execution response with history and variables omitted
     */
    public static ExecutionResponse accepted(WorkflowExecution execution) {
        return new ExecutionResponse(
                execution.getId(),
                execution.getWorkflowId(),
                execution.getWorkflowVersion(),
                execution.getWorkflowName(),
                String.valueOf(execution.getStatus()),
                String.valueOf(execution.getMode()),
                execution.getCurrentNodeId(),
                execution.getCurrentNodeType(),
                execution.getCurrentNodeStartedAt(),
                Map.of(), Map.of(), List.of(), null, null,
                execution.getStepCount(),
                execution.getStartedAt(), null, execution.getUpdatedAt(), execution.getCorrelationId());
    }

    /**
     * One node attempt.
     *
     * @param nodeId         node id
     * @param nodeType       node type
     * @param nodeName       display name
     * @param status         attempt outcome
     * @param attempt        attempt number
     * @param startedAt      start time
     * @param completedAt    completion time
     * @param durationMillis duration
     * @param selectedBranch branch chosen
     * @param errorCode      failure code
     * @param errorMessage   failure message
     * @param pluginId       plugin used
     * @param pluginVersion  plugin version used
     * @param outputs        node outputs, truncated
     */
    public record NodeHistoryView(String nodeId, String nodeType, String nodeName, String status, int attempt,
                                  Instant startedAt, Instant completedAt, long durationMillis,
                                  String selectedBranch, String errorCode, String errorMessage,
                                  String pluginId, String pluginVersion, Map<String, Object> outputs) {

        static NodeHistoryView from(NodeExecutionRecord record) {
            return new NodeHistoryView(record.getNodeId(), record.getNodeType(), record.getNodeName(),
                    record.getStatus(), record.getAttempt(), record.getStartedAt(), record.getCompletedAt(),
                    record.getDurationMillis(), record.getSelectedBranch(), record.getErrorCode(),
                    record.getErrorMessage(), record.getPluginId(), record.getPluginVersion(),
                    record.getOutputs());
        }
    }

    /**
     * What a waiting execution is waiting for.
     *
     * @param nodeId      node that parked
     * @param type        signal kind
     * @param formId      form to submit against
     * @param reason      human-readable reason
     * @param requestedAt when it parked
     * @param expiresAt   when waiting stops being acceptable
     * @param payload     data the client needs, such as prefilled values
     */
    public record PendingSignalView(String nodeId, String type, String formId, String reason,
                                    Instant requestedAt, Instant expiresAt, Map<String, Object> payload) {

        static PendingSignalView from(PendingSignal signal) {
            if (signal == null) {
                return null;
            }
            return new PendingSignalView(signal.getNodeId(), signal.getType(), signal.getFormId(),
                    signal.getReason(), signal.getRequestedAt(), signal.getExpiresAt(), signal.getPayload());
        }
    }

    /**
     * Why an execution failed.
     *
     * @param code    failure code
     * @param message failure message
     * @param nodeId  node that failed
     * @param at      when it failed
     */
    public record ErrorView(String code, String message, String nodeId, Instant at) {

        static ErrorView from(WorkflowExecution execution) {
            if (execution.getError() == null) {
                return null;
            }
            return new ErrorView(execution.getError().getCode(), execution.getError().getMessage(),
                    execution.getError().getNodeId(), execution.getError().getAt());
        }
    }
}
