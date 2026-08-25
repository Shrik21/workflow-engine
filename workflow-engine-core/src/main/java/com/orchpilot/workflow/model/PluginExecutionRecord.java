package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit and idempotency record for one plugin node invocation.
 *
 * <p>Two jobs in one collection. As an audit trail it answers "what did this plugin actually send".
 * As an idempotency ledger, the unique index on {@code idempotencyKey} means a retry or a
 * post-restart resume can find the prior successful attempt and replay its outputs instead of sending
 * a second email.
 *
 * <p>{@code request} and {@code response} are truncated and passed through secret redaction before
 * they are written. Credentials must never appear here.
 */
@Document(collection = "plugin_executions")
public class PluginExecutionRecord {

    @Id
    private String id;

    @Indexed
    private String executionId;

    private String workflowId;
    private int workflowVersion;
    private String nodeId;
    private String nodeType;

    @Indexed
    private String pluginId;

    private String pluginVersion;

    /**
     * Deterministic key for this node's side effect. Unique and sparse: a null key never collides,
     * which is what lets nodes declared idempotent skip the ledger entirely.
     */
    @Indexed(unique = true, sparse = true)
    private String idempotencyKey;

    private int attempt;
    private String status;
    private Instant startTime;
    private Instant endTime;
    private long durationMillis;

    private Map<String, Object> request = new LinkedHashMap<>();
    private Map<String, Object> response = new LinkedHashMap<>();

    private String errorCode;
    private String errorMessage;

    public PluginExecutionRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
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

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public void setPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public Map<String, Object> getRequest() {
        return request;
    }

    public void setRequest(Map<String, Object> request) {
        this.request = request == null ? new LinkedHashMap<>() : request;
    }

    public Map<String, Object> getResponse() {
        return response;
    }

    public void setResponse(Map<String, Object> response) {
        this.response = response == null ? new LinkedHashMap<>() : response;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
