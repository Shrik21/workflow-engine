package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The durable state of one workflow run.
 *
 * <p>Every node boundary writes this document. That costs a round trip per node and buys the ability
 * to resume after a crash at the last completed node rather than from the beginning, which for a
 * workflow that has already charged a card is the difference between correct and catastrophic.
 *
 * <p>{@code variables} holds the whole scoped namespace: {@code input}, {@code workflow},
 * {@code node} and {@code system} as top-level keys.
 */
@Document(collection = "workflow_executions")
@CompoundIndex(name = "execution_recovery", def = "{'status': 1, 'heartbeatAt': 1}")
@CompoundIndex(name = "execution_workflow", def = "{'workflowId': 1, 'startedAt': -1}")
public class WorkflowExecution {

    @Id
    private String id;

    @Indexed
    private String workflowId;

    private int workflowVersion;
    private String workflowName;

    @Indexed
    private ExecutionStatus status = ExecutionStatus.PENDING;

    private ExecutionMode mode = ExecutionMode.SYNCHRONOUS;

    /** Node the engine is on, or the node that parked or failed the execution. */
    private String currentNodeId;

    /**
     * Type of the node currently in flight, and when it started.
     *
     * <p>Written <em>before</em> the node executes, unlike {@link #nodeHistory}, which is only appended once a
     * node finishes. Without these a long-running node — an AI agent thinking, a deployment polling a cloud
     * operation — is invisible: the execution reads RUNNING while the timeline shows nothing, which looks like a
     * stuck engine rather than work in progress. They are cleared when the execution reaches a terminal state.
     */
    private String currentNodeType;
    private Instant currentNodeStartedAt;

    private Map<String, Object> variables = new LinkedHashMap<>();

    /** Values an end node published as the workflow result. */
    private Map<String, Object> output = new LinkedHashMap<>();

    private List<NodeExecutionRecord> nodeHistory = new ArrayList<>();

    private PendingSignal pendingSignal;
    private ExecutionError error;

    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;

    /**
     * The status this instance held before an administrator paused it, so resume restores it exactly.
     *
     * <p>An instance parked on a form is {@code WAITING}; one mid node-loop is {@code RUNNING}. Resume must send
     * each back where it was — a resumed form instance stays {@code WAITING} for its submission to drive it, a
     * resumed running instance re-enters the engine loop — so the pre-pause status is remembered rather than
     * assumed. Meaningful only while {@code status} is {@code PAUSED}.
     */
    private ExecutionStatus statusBeforePause;

    /** Why an administrator paused this instance, shown in its lifecycle history. Never a secret. */
    private String pauseReason;

    /** Why an administrator terminated this instance. Set once, when it moves to {@code TERMINATED}. */
    private String terminationReason;

    /** Who terminated it, and when. */
    private String terminatedBy;
    private Instant terminatedAt;

    /** Refreshed while running; a stale value lets another instance reclaim the execution. */
    @Indexed
    private Instant heartbeatAt;

    /** Engine instance that currently owns the run. */
    private String ownerInstance;

    /** Nodes executed so far, checked against the engine's step ceiling. */
    private int stepCount;

    /** Username of whoever started this run, or {@code system} for a scheduled or event-driven start. */
    private String triggeredBy;

    /**
     * User id of whoever started this run. Null for an engine-initiated execution.
     *
     * <p>Stored alongside the username because a username can be changed while an id cannot, and the audit
     * question "who ran this" has to stay answerable afterwards.
     */
    private String triggeredByUserId;

    /**
     * Role names held at the moment the execution started.
     *
     * <p>A snapshot, not a live view. A long-running workflow should behave consistently from start to
     * finish rather than changing part-way through because someone's role was edited, and the record of what
     * was permitted at the time is what an audit needs.
     */
    private java.util.List<String> triggeredByRoles = java.util.List.of();
    private String triggerId;

    /** Caller-supplied correlation id, echoed in logs and events. */
    @Indexed
    private String correlationId;

    /** Caller-supplied key that makes {@code start} idempotent at the execution level. */
    @Indexed(unique = true, sparse = true)
    private String idempotencyKey;

    @Version
    private Long documentVersion;

    public WorkflowExecution() {
    }

    /**
     * @param nodeId    node the record belongs to
     * @param maxRecords cap on retained history; oldest records are dropped first
     * @param record    record to append
     */
    public void appendHistory(String nodeId, int maxRecords, NodeExecutionRecord record) {
        record.setNodeId(nodeId);
        nodeHistory.add(record);
        while (maxRecords > 0 && nodeHistory.size() > maxRecords) {
            nodeHistory.remove(0);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(int workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public ExecutionMode getMode() {
        return mode;
    }

    public void setMode(ExecutionMode mode) {
        this.mode = mode;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public String getCurrentNodeType() {
        return currentNodeType;
    }

    public void setCurrentNodeType(String currentNodeType) {
        this.currentNodeType = currentNodeType;
    }

    public Instant getCurrentNodeStartedAt() {
        return currentNodeStartedAt;
    }

    public void setCurrentNodeStartedAt(Instant currentNodeStartedAt) {
        this.currentNodeStartedAt = currentNodeStartedAt;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new LinkedHashMap<>() : variables;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output == null ? new LinkedHashMap<>() : output;
    }

    public List<NodeExecutionRecord> getNodeHistory() {
        return nodeHistory;
    }

    public void setNodeHistory(List<NodeExecutionRecord> nodeHistory) {
        this.nodeHistory = nodeHistory == null ? new ArrayList<>() : nodeHistory;
    }

    public PendingSignal getPendingSignal() {
        return pendingSignal;
    }

    public void setPendingSignal(PendingSignal pendingSignal) {
        this.pendingSignal = pendingSignal;
    }

    public ExecutionError getError() {
        return error;
    }

    public void setError(ExecutionError error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public ExecutionStatus getStatusBeforePause() {
        return statusBeforePause;
    }

    public void setStatusBeforePause(ExecutionStatus statusBeforePause) {
        this.statusBeforePause = statusBeforePause;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(String pauseReason) {
        this.pauseReason = pauseReason;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        this.terminationReason = terminationReason;
    }

    public String getTerminatedBy() {
        return terminatedBy;
    }

    public void setTerminatedBy(String terminatedBy) {
        this.terminatedBy = terminatedBy;
    }

    public Instant getTerminatedAt() {
        return terminatedAt;
    }

    public void setTerminatedAt(Instant terminatedAt) {
        this.terminatedAt = terminatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public String getOwnerInstance() {
        return ownerInstance;
    }

    public void setOwnerInstance(String ownerInstance) {
        this.ownerInstance = ownerInstance;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public String getTriggeredByUserId() {
        return triggeredByUserId;
    }

    public void setTriggeredByUserId(String triggeredByUserId) {
        this.triggeredByUserId = triggeredByUserId;
    }

    public java.util.List<String> getTriggeredByRoles() {
        return triggeredByRoles;
    }

    public void setTriggeredByRoles(java.util.List<String> triggeredByRoles) {
        this.triggeredByRoles = triggeredByRoles == null
                ? java.util.List.of()
                : java.util.List.copyOf(triggeredByRoles);
    }

    public String getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(String triggerId) {
        this.triggerId = triggerId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }
}
