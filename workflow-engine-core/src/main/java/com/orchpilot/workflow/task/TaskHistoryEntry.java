package com.orchpilot.workflow.task;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing that happened to a task.
 *
 * <p>Append-only. Nothing updates one of these, which is what makes it worth reading: an approval trail that
 * can be edited answers no question that mattered.
 *
 * <h2>What is not recorded here</h2>
 *
 * <p>Submitted values. The history says a task was completed and by whom; the values live on the task itself,
 * where they are subject to the same authorization as the rest of it. Copying them into every history entry
 * would scatter whatever the form collected — which in a real deployment is names, salaries and addresses —
 * across a second collection with its own retention.
 */
@Document(collection = "human_task_history")
public class TaskHistoryEntry {

    @Id
    private String id;

    @Indexed
    private String taskId;

    private String workflowExecutionId;

    private TaskAction action;

    /** Username of whoever acted, or {@code system} for the scheduler. */
    private String actor;

    private String actorUserId;

    private Instant at;

    /** Free-text note, for a reassignment reason or a cancellation. */
    private String comment;

    /** Structured context. Field names, never field values. */
    private Map<String, Object> details = new LinkedHashMap<>();

    public TaskHistoryEntry() {
    }

    /**
     * @param task    the task acted on
     * @param action  what happened
     * @param actor   username of whoever did it
     * @param userId  their user id, or null for the scheduler
     * @param comment optional note
     * @param details structured context; must not contain submitted values
     * @return the entry, not yet persisted
     */
    public static TaskHistoryEntry of(HumanTask task, TaskAction action, String actor, String userId,
                                      String comment, Map<String, Object> details) {
        TaskHistoryEntry entry = new TaskHistoryEntry();
        entry.setTaskId(task.getId());
        entry.setWorkflowExecutionId(task.getWorkflowExecutionId());
        entry.setAction(action);
        entry.setActor(actor);
        entry.setActorUserId(userId);
        entry.setComment(comment);
        entry.setDetails(details);
        entry.setAt(Instant.now());
        return entry;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public TaskAction getAction() {
        return action;
    }

    public void setAction(TaskAction action) {
        this.action = action;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    }
}
