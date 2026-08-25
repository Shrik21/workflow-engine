package com.orchpilot.workflow.task.dto;

import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.task.HumanTask;
import com.orchpilot.workflow.task.TaskHistoryEntry;
import com.orchpilot.workflow.task.TaskStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the task API returns.
 *
 * <p>Records rather than the documents themselves, and for once not out of habit. A {@link HumanTask} carries
 * {@code prefill} and {@code submittedData} — whatever the form asked a person for — and a summary row in an
 * inbox has no business carrying either. Serialising the document would send every value of every task in the
 * list to a client that only needed the titles.
 *
 * <p>The two shapes reflect that: {@link Summary} has no values at all, {@link Detail} has them and is returned
 * one task at a time by an endpoint that checks who is asking.
 */
public final class TaskResponses {

    private TaskResponses() {
    }

    /**
     * One row in an inbox. No form values.
     *
     * @param taskId            the task
     * @param executionId       the execution it belongs to
     * @param workflowId        its workflow
     * @param workflowName      for display
     * @param workflowVersion   the pinned workflow version
     * @param nodeId            the node that raised it
     * @param taskName          what the row says
     * @param description       one-line context
     * @param status            where it is in its life
     * @param priority          urgency
     * @param assigneeUsername  who holds it, or null
     * @param assignedToMe      whether that is the caller
     * @param claimable         whether the caller could take it
     * @param candidateGroups   how many groups it was offered to
     * @param formDefinitionId  the form, or null when the node references none
     * @param formVersion       the pinned form version
     * @param createdAt         when it was raised
     * @param dueAt             advisory deadline
     * @param expiresAt         hard deadline
     * @param overdue           whether the advisory deadline has passed
     * @param hasDraft          whether the holder saved partial input
     * @param completedAt       when it was submitted
     * @param completedBy       who submitted it
     */
    public record Summary(
            String taskId,
            String executionId,
            String workflowId,
            String workflowName,
            int workflowVersion,
            String nodeId,
            String taskName,
            String description,
            TaskStatus status,
            String priority,
            String assigneeUsername,
            boolean assignedToMe,
            boolean claimable,
            int candidateGroups,
            String formDefinitionId,
            int formVersion,
            Instant createdAt,
            Instant dueAt,
            Instant expiresAt,
            boolean overdue,
            boolean hasDraft,
            Instant completedAt,
            String completedBy,
            boolean external) {

        /**
         * @param task      the task
         * @param userId    the caller's id, for the "mine" flag
         * @param claimable whether the caller may claim it
         * @return the row
         */
        public static Summary of(HumanTask task, String userId, boolean claimable) {
            return new Summary(
                    task.getId(), task.getWorkflowExecutionId(), task.getWorkflowId(), task.getWorkflowName(),
                    task.getWorkflowVersion(), task.getNodeId(), task.getTaskName(), task.getDescription(),
                    task.getStatus(), task.getPriority().name(), task.getAssigneeUsername(),
                    task.isAssignedTo(userId), claimable, task.getCandidateGroupIds().size(),
                    task.getFormDefinitionId(), task.getFormVersion(), task.getCreatedAt(), task.getDueAt(),
                    task.getExpiresAt(), task.isOverdue(), !task.getDraftData().isEmpty(),
                    task.getCompletedAt(), task.getCompletedByUsername(), task.isExternal());
        }
    }

    /**
     * One task, with everything needed to render and submit it.
     *
     * @param task         the summary fields
     * @param form         the pinned form version to render, or null
     * @param formIssue    why {@code form} is null, when it is
     * @param initialData  what to put in the controls: the prefill, overlaid with any saved draft
     * @param submittedData what was submitted, present only once the task is complete
     * @param capabilities what this caller may do
     * @param history      the trail, oldest first
     */
    public record Detail(
            Summary task,
            FormVersion form,
            String formIssue,
            Map<String, Object> initialData,
            Map<String, Object> submittedData,
            Capabilities capabilities,
            List<HistoryEntry> history) {
    }

    /**
     * What the caller may do, so the console can render the right buttons.
     *
     * <p>A courtesy, not a control. Every one of these is checked again by the endpoint that performs the action;
     * a client that ignored them would simply be refused.
     *
     * @param claim    take an open task
     * @param release  give it back to its candidates
     * @param complete submit it
     * @param saveDraft store partial input
     * @param reassign move it to somebody else
     * @param cancel   withdraw it, which cancels the execution
     */
    public record Capabilities(boolean claim, boolean release, boolean complete, boolean saveDraft,
                               boolean reassign, boolean cancel) {
    }

    /**
     * One history line.
     *
     * @param action  what happened
     * @param actor   who did it
     * @param at      when
     * @param comment their note, if any
     * @param details structured context; field names, never values
     */
    public record HistoryEntry(String action, String actor, Instant at, String comment,
                               Map<String, Object> details) {

        public static HistoryEntry of(TaskHistoryEntry entry) {
            return new HistoryEntry(entry.getAction() == null ? null : entry.getAction().name(),
                    entry.getActor(), entry.getAt(), entry.getComment(), entry.getDetails());
        }
    }

    /**
     * The values to show in the form when it opens.
     *
     * <p>The draft wins over the prefill, key by key rather than wholesale. A draft saved before a read-only
     * field's variable was recomputed would otherwise show a stale value for something the user cannot correct.
     * Merging per key means the workflow's current answer survives for fields the user never touched.
     *
     * @param task the task
     * @return prefill overlaid with the draft
     */
    public static Map<String, Object> initialDataFor(HumanTask task) {
        Map<String, Object> merged = new LinkedHashMap<>(task.getPrefill());
        merged.putAll(task.getDraftData());
        return merged;
    }
}
