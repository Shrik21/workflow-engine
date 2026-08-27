package com.orchpilot.workflow.task;

import java.time.Duration;
import java.util.List;

/**
 * Who a task goes to, resolved from a form node's configuration.
 *
 * <p>A resolved value object, not the raw configuration: by the time one of these exists, every placeholder has
 * been substituted, every username has been turned into a user id, and every group name into a group id. The
 * service that creates the task therefore has nothing left to interpret, which is what keeps the interpretation
 * in one place rather than spread between the resolver, the service and the executor.
 *
 * @param assigneeUserId   the single accountable person, or null to offer the task to candidates
 * @param assigneeUsername their username, denormalised for display
 * @param candidateUserIds users who may claim it
 * @param candidateGroupIds groups whose members may claim it
 * @param priority         urgency, never null
 * @param dueIn            advisory deadline measured from creation, or null
 * @param expiresIn        hard deadline measured from creation, or null
 * @param problems         what could not be resolved; the task is still created, unassigned, and these are
 *                         written to the execution log and the task's history so the misconfiguration is
 *                         visible rather than silent
 * @param external         whether this task goes to an external customer via a secure form link rather than to
 *                         an internal user or group; such a task has no internal addressee by design
 */
public record TaskAssignment(
        String assigneeUserId,
        String assigneeUsername,
        List<String> candidateUserIds,
        List<String> candidateGroupIds,
        TaskPriority priority,
        Duration dueIn,
        Duration expiresIn,
        List<String> problems,
        boolean external) {

    public TaskAssignment {
        candidateUserIds = candidateUserIds == null ? List.of() : List.copyOf(candidateUserIds);
        candidateGroupIds = candidateGroupIds == null ? List.of() : List.copyOf(candidateGroupIds);
        priority = priority == null ? TaskPriority.NORMAL : priority;
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** @return whether one person is directly accountable */
    public boolean isDirectlyAssigned() {
        return assigneeUserId != null && !assigneeUserId.isBlank();
    }

    /**
     * @return whether the task reaches somebody
     *
     * <p>An external task is addressed even though it names no internal user: its recipient is the customer who
     * holds the form link, so it must not draw the "nobody can see this" warning an unaddressed internal task
     * does.
     */
    public boolean isAddressed() {
        return external || isDirectlyAssigned() || !candidateUserIds.isEmpty()
                || !candidateGroupIds.isEmpty();
    }

    /**
     * The status a task with this assignment starts in.
     *
     * <p>Directly assigned tasks skip {@link TaskStatus#OPEN} entirely. Making somebody claim work that was
     * addressed to them by name is a step that conveys nothing.
     */
    public TaskStatus initialStatus() {
        return isDirectlyAssigned() ? TaskStatus.ASSIGNED : TaskStatus.OPEN;
    }
}
