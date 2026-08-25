package com.orchpilot.workflow.pluginserver;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One entry in the installation history of this engine.
 *
 * <h2>Separate from the audit trail on purpose</h2>
 *
 * <p>The audit trail answers "who did what", across every kind of administrative action, and is written for a
 * reviewer. This answers "how did this engine come to be running these plugin versions", and is written for whoever
 * is looking at a plugin that stopped working. The questions overlap and the readers do not: an operator debugging a
 * failed update should not have to page through login records to find the three rows that matter.
 *
 * <p>Failures are recorded as loudly as successes. An install that failed its checksum, or an update that could not
 * drain the old version, is the row somebody will go looking for.
 */
@Document(collection = "plugin_installation_history")
@CompoundIndex(name = "plugin_history_recent", def = "{'pluginId': 1, 'at': -1}")
public class PluginInstallation {

    /** What was done. */
    public enum Action {

        /** A version this engine did not have was downloaded and installed. */
        INSTALL,

        /** A newer version was installed and the default moved to it. */
        UPDATE,

        /** A version was unloaded and removed. */
        UNINSTALL,

        /** An installed version was loaded and its node types registered. */
        ACTIVATE,

        /** An installed version was unloaded and its node types withdrawn. */
        DEACTIVATE
    }

    /** How it went. {@code REFUSED} is a deliberate no: a dependency check said stop, and nothing was changed. */
    public enum Outcome {
        OK, FAILED, REFUSED
    }

    @Id
    private String id;

    private String pluginId;
    private String version;

    /** For an update, the version that was in use before it. Null otherwise. */
    private String fromVersion;

    private Action action;
    private Outcome outcome;

    /** The verified SHA-256, so the history is enough to tell which bytes were installed. */
    private String checksum;

    /** Why it failed or was refused, or a short note about what happened. Never carries a secret. */
    private String detail;

    private String actor;
    private Instant at;
    private long durationMillis;

    public PluginInstallation() {
    }

    public static PluginInstallation of(String pluginId, String version, Action action, Outcome outcome,
                                        String actor) {
        PluginInstallation record = new PluginInstallation();
        record.pluginId = pluginId;
        record.version = version;
        record.action = action;
        record.outcome = outcome;
        record.actor = actor;
        record.at = Instant.now();
        return record;
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

    public String getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(String fromVersion) {
        this.fromVersion = fromVersion;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /** @return the coordinate this row is about */
    public String coordinate() {
        return pluginId + ":" + version;
    }
}
