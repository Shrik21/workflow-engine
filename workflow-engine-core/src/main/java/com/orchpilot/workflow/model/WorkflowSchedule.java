package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A materialised cron trigger.
 *
 * <p>{@code nextRunAt} is both the query key and the lock. The scheduler claims a due schedule with a
 * single atomic {@code findAndModify} that advances {@code nextRunAt} to the following fire time; only
 * one instance in the cluster can win that write, so N engine replicas fire a cron exactly once
 * without needing a leader election or an external scheduler.
 */
@Document(collection = "workflow_schedules")
@CompoundIndex(name = "schedule_workflow_trigger", def = "{'workflowId': 1, 'triggerId': 1}", unique = true)
public class WorkflowSchedule {

    @Id
    private String id;

    private String workflowId;
    private String triggerId;

    /** Version to execute; refreshed on publish so schedules follow the published version. */
    private int workflowVersion;

    private String cron;
    private String timezone;
    private boolean enabled = true;

    /** Next due time, and the field the cluster-safe claim operates on. */
    @Indexed
    private Instant nextRunAt;

    private Instant lastRunAt;
    private String lastExecutionId;
    private String lastError;
    private long fireCount;

    private Map<String, Object> defaultInput = new LinkedHashMap<>();

    public WorkflowSchedule() {
    }

    /**
     * @param workflowId workflow id
     * @param triggerId  trigger id within the workflow
     * @return the deterministic document id for this coordinate
     */
    public static String idFor(String workflowId, String triggerId) {
        return workflowId + ":" + triggerId;
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

    public String getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(String triggerId) {
        this.triggerId = triggerId;
    }

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(int workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastExecutionId() {
        return lastExecutionId;
    }

    public void setLastExecutionId(String lastExecutionId) {
        this.lastExecutionId = lastExecutionId;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public long getFireCount() {
        return fireCount;
    }

    public void setFireCount(long fireCount) {
        this.fireCount = fireCount;
    }

    public Map<String, Object> getDefaultInput() {
        return defaultInput;
    }

    public void setDefaultInput(Map<String, Object> defaultInput) {
        this.defaultInput = defaultInput == null ? new LinkedHashMap<>() : defaultInput;
    }
}
