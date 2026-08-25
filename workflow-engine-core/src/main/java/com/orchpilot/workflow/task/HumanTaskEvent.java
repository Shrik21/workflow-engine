package com.orchpilot.workflow.task;

import java.time.Instant;
import java.util.List;

/**
 * Something happened to a task, published for anything that wants to react.
 *
 * <p>Deliberately carries no submitted data. A listener that wants the values can read the task, subject to the
 * same authorization as any other reader; putting them on the event would broadcast whatever the form collected
 * to every listener in the context, including ones added later for unrelated reasons.
 *
 * <p>Published through Spring's {@code ApplicationEventPublisher} rather than the engine's own
 * {@code WorkflowEventPublisher}, because these are internal integration points — notifications, metrics — and
 * not workflow triggers. A task completion that started another workflow would be surprising.
 *
 * @param action             what happened
 * @param taskId             the task
 * @param workflowExecutionId the execution it belongs to
 * @param nodeId             the node that raised it
 * @param assigneeUserId     who was accountable at the time of the event, or null
 * @param candidateGroupIds  groups it was offered to
 * @param actor              username of whoever acted, or {@code system}
 * @param at                 when
 */
public record HumanTaskEvent(
        TaskAction action,
        String taskId,
        String workflowExecutionId,
        String nodeId,
        String assigneeUserId,
        List<String> candidateGroupIds,
        String actor,
        Instant at) {

    public HumanTaskEvent {
        candidateGroupIds = candidateGroupIds == null ? List.of() : List.copyOf(candidateGroupIds);
        at = at == null ? Instant.now() : at;
    }

    /**
     * @param task   the task as it now stands
     * @param action what happened to it
     * @param actor  who did it
     * @return the event
     */
    public static HumanTaskEvent of(HumanTask task, TaskAction action, String actor) {
        return new HumanTaskEvent(action, task.getId(), task.getWorkflowExecutionId(), task.getNodeId(),
                task.getAssigneeUserId(), task.getCandidateGroupIds(), actor, Instant.now());
    }
}
