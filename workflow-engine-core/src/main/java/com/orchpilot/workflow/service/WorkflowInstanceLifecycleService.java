package com.orchpilot.workflow.service;

import com.orchpilot.workflow.event.ExecutionLifecycleEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.exception.InvalidWorkflowStateException;
import com.orchpilot.workflow.execution.ExecutionStateStore;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.task.TaskLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pause, resume and terminate a single workflow <em>instance</em> — never the template it runs.
 *
 * <h2>The instance is the boundary</h2>
 *
 * Every operation here acts on one {@code WorkflowExecution} and cascades only to the runtime tasks that carry
 * its id. Two instances of the same workflow are independent: terminating one leaves the others running. The
 * workflow definition is not touched at all — this is runtime state, not design.
 *
 * <h2>Atomic first, cascade second</h2>
 *
 * Each transition flips the instance status with a single conditional write that succeeds only from a legal
 * source status ({@link ExecutionStateStore#transitionStatus}), and only then cascades to the tasks. That order
 * is what makes the concurrent cases correct: if a terminate and a form submit race, whichever conditional
 * write lands first decides the outcome — a submit that arrives after the flip sees {@code TERMINATED} and is
 * refused, and a terminate that arrives after a submit finds the task already {@code COMPLETED} and leaves it
 * alone. No lock is held across the two steps because none is needed; the status write is the arbitration point.
 *
 * <h2>Stopping the engine without killing a thread</h2>
 *
 * Neither pause nor terminate interrupts a node that is mid-execution. The engine re-reads the persisted status
 * at every node boundary, so a paused or terminated instance stops before its <em>next</em> node — the current
 * operation is allowed to finish, and the continuation point (the current node) is already durable, so resume
 * picks up exactly where it left off.
 */
@Service
public class WorkflowInstanceLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInstanceLifecycleService.class);

    /** The statuses a pause is legal from. */
    private static final Set<ExecutionStatus> PAUSABLE_FROM =
            Set.of(ExecutionStatus.RUNNING, ExecutionStatus.WAITING, ExecutionStatus.PENDING);

    /** The statuses a terminate is legal from — everything still live, paused included. */
    private static final Set<ExecutionStatus> TERMINABLE_FROM = Set.of(ExecutionStatus.RUNNING,
            ExecutionStatus.WAITING, ExecutionStatus.PENDING, ExecutionStatus.PAUSED);

    private final ExecutionStateStore stateStore;
    private final ExecutionService executions;
    private final TaskLifecycleService taskLifecycle;
    private final AuditService audit;
    private final WorkflowEventPublisher eventPublisher;

    public WorkflowInstanceLifecycleService(ExecutionStateStore stateStore, ExecutionService executions,
                                            TaskLifecycleService taskLifecycle, AuditService audit,
                                            WorkflowEventPublisher eventPublisher) {
        this.stateStore = stateStore;
        this.executions = executions;
        this.taskLifecycle = taskLifecycle;
        this.audit = audit;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Pauses an instance and holds its active tasks.
     *
     * @param instanceId the instance
     * @param reason     why, for the lifecycle history; may be null
     * @param actor      who paused it
     * @return the instance in its new state
     */
    public WorkflowExecution pause(String instanceId, String reason, String actor) {
        WorkflowExecution instance = stateStore.require(instanceId);
        ExecutionStatus from = instance.getStatus();

        if (from == ExecutionStatus.PAUSED) {
            return instance; // Already paused: idempotent, as double-pause must be.
        }
        if (from.isTerminal()) {
            throw new InvalidWorkflowStateException("Workflow instance '" + instanceId + "' is " + from
                    + " and cannot be paused.");
        }

        boolean moved = stateStore.transitionStatus(instanceId, Set.of(from), ExecutionStatus.PAUSED,
                update -> update.set("statusBeforePause", from).set("pauseReason", reason));
        if (!moved) {
            return reconcile(instanceId, ExecutionStatus.PAUSED, "pause");
        }

        int paused = taskLifecycle.pauseTasksFor(instanceId, actor);
        audit.record(actor, "INSTANCE_PAUSED", "WORKFLOW_INSTANCE", instanceId, "OK",
                details(instance, from, ExecutionStatus.PAUSED, reason, Map.of("tasksPaused", paused)));
        log.info("Workflow instance {} paused by {} ({} task(s) held)", instanceId, actor, paused);
        return stateStore.require(instanceId);
    }

    /**
     * Resumes a paused instance and returns its held tasks to their pre-pause status.
     *
     * <p>Where it goes next depends on where it was: an instance paused while parked on a form returns to
     * {@code WAITING}, so its form submission drives it onward, while one paused mid node-loop re-enters the
     * engine to continue executing. A terminated instance is never resumable by this or any path.
     *
     * @param instanceId the instance
     * @param actor      who resumed it
     * @return the instance in its new state
     */
    public WorkflowExecution resume(String instanceId, String actor) {
        WorkflowExecution instance = stateStore.require(instanceId);
        if (instance.getStatus() != ExecutionStatus.PAUSED) {
            throw new InvalidWorkflowStateException("Workflow instance '" + instanceId + "' is "
                    + instance.getStatus() + " and cannot be resumed; only a paused instance can be resumed.");
        }

        int restored = taskLifecycle.resumeTasksFor(instanceId, actor);
        ExecutionStatus before = instance.getStatusBeforePause();

        WorkflowExecution result;
        if (before == ExecutionStatus.WAITING) {
            // Parked on a form: return it to WAITING, leaving the pending signal intact so the submission
            // continues the run. No engine re-entry — there was no loop to re-enter.
            stateStore.transitionStatus(instanceId, Set.of(ExecutionStatus.PAUSED), ExecutionStatus.WAITING,
                    update -> update.set("statusBeforePause", null).set("pauseReason", null));
            result = stateStore.require(instanceId);
        } else {
            // Mid node-loop (or never started): hand it back to the execution service, which flips PAUSED to
            // RUNNING and re-enters the engine on a pool thread.
            result = executions.resume(instanceId, true, actor);
        }

        audit.record(actor, "INSTANCE_RESUMED", "WORKFLOW_INSTANCE", instanceId, "OK",
                details(instance, ExecutionStatus.PAUSED, result.getStatus(), null,
                        Map.of("tasksRestored", restored)));
        log.info("Workflow instance {} resumed by {} to {} ({} task(s) restored)", instanceId, actor,
                result.getStatus(), restored);
        return result;
    }

    /**
     * Terminates an instance permanently and ends its active tasks.
     *
     * @param instanceId the instance
     * @param reason     why, recorded on the instance and its tasks; may be null
     * @param actor      who terminated it
     * @return the instance in its terminal state
     */
    public WorkflowExecution terminate(String instanceId, String reason, String actor) {
        WorkflowExecution instance = stateStore.require(instanceId);
        ExecutionStatus from = instance.getStatus();

        if (from == ExecutionStatus.TERMINATED) {
            return instance; // Already terminated: idempotent, as double-terminate must be.
        }
        if (from.isTerminal()) {
            throw new InvalidWorkflowStateException("Workflow instance '" + instanceId + "' is " + from
                    + " and cannot be terminated.");
        }

        Instant now = Instant.now();
        boolean moved = stateStore.transitionStatus(instanceId, TERMINABLE_FROM, ExecutionStatus.TERMINATED,
                update -> update.set("terminatedBy", actor).set("terminatedAt", now)
                        .set("terminationReason", reason).set("completedAt", now).set("pendingSignal", null));
        if (!moved) {
            return reconcile(instanceId, ExecutionStatus.TERMINATED, "terminate");
        }

        int terminatedTasks = taskLifecycle.terminateTasksFor(instanceId, actor, reason);

        // Announce it so a loop running on another instance stops at its next boundary. The task listener
        // deliberately ignores TERMINATED — the tasks are already terminated above — so this is purely the
        // cross-instance stop signal.
        eventPublisher.publishExecutionEvent(ExecutionLifecycleEvent.of(instanceId, instance.getWorkflowId(),
                instance.getWorkflowVersion(), ExecutionStatus.TERMINATED, instance.getCurrentNodeId()));

        audit.record(actor, "INSTANCE_TERMINATED", "WORKFLOW_INSTANCE", instanceId, "OK",
                details(instance, from, ExecutionStatus.TERMINATED, reason,
                        Map.of("tasksTerminated", terminatedTasks)));
        log.info("Workflow instance {} terminated by {} ({} task(s) terminated)", instanceId, actor,
                terminatedTasks);
        return stateStore.require(instanceId);
    }

    /**
     * @param instanceId the instance
     * @return its current runtime state
     */
    public WorkflowExecution status(String instanceId) {
        return stateStore.require(instanceId);
    }

    /**
     * Re-reads an instance after a conditional transition did not match, to tell an idempotent success from a
     * genuine conflict.
     */
    private WorkflowExecution reconcile(String instanceId, ExecutionStatus intended, String operation) {
        WorkflowExecution now = stateStore.require(instanceId);
        if (now.getStatus() == intended) {
            return now; // Another writer reached the same state first; treat as success.
        }
        throw new InvalidWorkflowStateException("Workflow instance '" + instanceId + "' changed to "
                + now.getStatus() + " during the " + operation + " and is now " + now.getStatus() + ".");
    }

    private static Map<String, Object> details(WorkflowExecution instance, ExecutionStatus from,
                                               ExecutionStatus to, String reason, Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowInstanceId", instance.getId());
        details.put("workflowTemplateId", instance.getWorkflowId());
        details.put("previousStatus", from == null ? null : from.name());
        details.put("newStatus", to.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        details.putAll(extra);
        return details;
    }
}
