package com.orchpilot.workflow.model;

import java.time.Instant;

/**
 * The failure that ended an execution.
 */
public class ExecutionError {

    private String code;
    private String message;
    private String nodeId;
    private Instant at;
    private boolean retryable;

    public ExecutionError() {
    }

    public ExecutionError(String code, String message, String nodeId) {
        this.code = code;
        this.message = message;
        this.nodeId = nodeId;
        this.at = Instant.now();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }
}
