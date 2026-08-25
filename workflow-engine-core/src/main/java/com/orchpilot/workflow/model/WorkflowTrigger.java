package com.orchpilot.workflow.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declares a way this workflow can be started.
 *
 * <p>Publishing a workflow reconciles its SCHEDULE triggers into the {@code workflow_schedules}
 * collection and its EVENT triggers into the event dispatcher's index. Unpublishing removes them, so
 * the trigger list on the definition is the single source of truth.
 */
public class WorkflowTrigger {

    private String id;
    private TriggerType type = TriggerType.MANUAL;
    private boolean enabled = true;

    /** Six-field Spring cron expression, for {@link TriggerType#SCHEDULE}. */
    private String cron;

    /** Zone the cron expression is interpreted in; defaults to UTC when absent. */
    private String timezone;

    /**
     * The friendly schedule choices the operator made, stored alongside {@link #cron} so an edit re-opens the
     * same dropdowns rather than the raw expression. Present for {@link TriggerType#SCHEDULE} triggers created
     * through the friendly builder; a legacy trigger has only the cron, from which the builder reconstructs a
     * best-effort configuration.
     */
    private com.orchpilot.workflow.scheduler.ScheduleConfig schedule;

    /** Event name, for {@link TriggerType#EVENT}. */
    private String eventName;

    /** Input merged beneath the runtime input, letting a schedule supply fixed parameters. */
    private Map<String, Object> defaultInput = new LinkedHashMap<>();

    public WorkflowTrigger() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TriggerType getType() {
        return type;
    }

    public void setType(TriggerType type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public com.orchpilot.workflow.scheduler.ScheduleConfig getSchedule() {
        return schedule;
    }

    public void setSchedule(com.orchpilot.workflow.scheduler.ScheduleConfig schedule) {
        this.schedule = schedule;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public Map<String, Object> getDefaultInput() {
        return defaultInput;
    }

    public void setDefaultInput(Map<String, Object> defaultInput) {
        this.defaultInput = defaultInput == null ? new LinkedHashMap<>() : defaultInput;
    }
}
