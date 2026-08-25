package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An administrative action worth being able to prove later.
 *
 * <p>Plugin installation, activation, deactivation, deletion, secret writes and secret reads by
 * plugins all land here. For an extension platform this is not optional bookkeeping: when a plugin
 * misbehaves, the first question is who installed which version, when, and what it was allowed to
 * reach.
 */
@Document(collection = "workflow_audit_log")
@CompoundIndex(name = "audit_entity", def = "{'entityType': 1, 'entityId': 1, 'at': -1}")
public class AuditRecord {

    @Id
    private String id;

    @Indexed
    private Instant at;

    private String actor;
    private String action;
    private String entityType;
    private String entityId;
    private String outcome;
    private Map<String, Object> details = new LinkedHashMap<>();

    public AuditRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new LinkedHashMap<>() : details;
    }
}
