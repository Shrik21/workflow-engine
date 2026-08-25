package com.orchpilot.workflow.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@code WAITING} execution is waiting for.
 *
 * <p>Persisting this, rather than holding a blocked thread, is what makes human-in-the-loop steps
 * survive a restart: the execution occupies no resources while parked and any instance in the cluster
 * can resume it.
 */
public class PendingSignal {

    /** Node that parked the execution. */
    private String nodeId;

    /** Signal kind, e.g. {@code FORM}. Plugin nodes may park with their own kinds. */
    private String type;

    /** Form identifier a client submits against, for form nodes. */
    private String formId;

    private String reason;
    private Instant requestedAt;

    /** When set and passed, the execution fails with a timeout rather than waiting forever. */
    private Instant expiresAt;

    /** Data the client needs to render the form, e.g. prefilled values from input mapping. */
    private Map<String, Object> payload = new LinkedHashMap<>();

    public PendingSignal() {
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFormId() {
        return formId;
    }

    public void setFormId(String formId) {
        this.formId = formId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new LinkedHashMap<>() : payload;
    }
}
