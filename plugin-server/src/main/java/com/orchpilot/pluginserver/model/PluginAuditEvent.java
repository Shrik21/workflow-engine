package com.orchpilot.pluginserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened in the registry.
 *
 * <p>Append-only. For a service whose contents are executable code, the audit trail is a functional requirement
 * rather than compliance decoration: when a plugin misbehaves, the first questions are who published which version,
 * when, and which services have downloaded it since.
 *
 * <p>Downloads are recorded as well as changes. That is the difference between knowing a bad version existed and
 * knowing where it went.
 */
@Document(collection = "plugin_audit")
public class PluginAuditEvent {

    /** What happened. */
    public enum Action {
        UPLOADED,
        PUBLISHED,
        DEACTIVATED,
        DEPRECATED,
        REVOKED,
        DELETED,
        DOWNLOADED,
        UPLOAD_REJECTED
    }

    @Id
    private String id;

    @Indexed
    private String pluginId;

    private String version;

    private Action action;

    /** Username for a person, client id for a service. */
    private String actor;

    private String outcome;

    private Instant at;

    /** Structured context. Never the archive's bytes, and never a token. */
    private Map<String, Object> details = new LinkedHashMap<>();

    public static PluginAuditEvent of(String pluginId, String version, Action action, String actor,
                                      String outcome, Map<String, Object> details) {
        PluginAuditEvent event = new PluginAuditEvent();
        event.pluginId = pluginId;
        event.version = version;
        event.action = action;
        event.actor = actor;
        event.outcome = outcome;
        event.at = Instant.now();
        event.setDetails(details);
        return event;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    }
}
