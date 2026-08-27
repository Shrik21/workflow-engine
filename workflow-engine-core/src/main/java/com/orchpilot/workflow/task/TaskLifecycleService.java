package com.orchpilot.workflow.task;

import com.orchpilot.workflow.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cascades a workflow instance's lifecycle onto the runtime tasks that belong to it.
 *
 * <h2>Only this instance's tasks, and never a finished one</h2>
 *
 * Every method here selects strictly by {@code workflowExecutionId}, so pausing instance A can never touch a
 * task on instance B of the same workflow template. And every method selects by status as well: an already
 * {@code COMPLETED} task is out of every result set, so a submitted approval is never rewritten by a later
 * pause or terminate of the instance it belonged to. These two filters are the specification's rules 4 and 5,
 * enforced at the query rather than trusted to the caller.
 *
 * <h2>No cycle back into the engine</h2>
 *
 * This service mutates tasks and nothing else. It never resumes, cancels or advances an execution — that stays
 * with the instance-lifecycle service that calls this one — which is what keeps it free of the task→execution
 * cycle the rest of the task package is careful about.
 */
@Service
public class TaskLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleService.class);

    /** Active tasks a pause holds: offered or owned, but not yet finished. */
    private static final Set<TaskStatus> PAUSABLE = EnumSet.of(TaskStatus.OPEN, TaskStatus.ASSIGNED);

    /** Tasks a terminate ends: everything still live, including ones already paused. */
    private static final Set<TaskStatus> TERMINABLE =
            EnumSet.of(TaskStatus.OPEN, TaskStatus.ASSIGNED, TaskStatus.PAUSED);

    private final HumanTaskService tasks;
    private final HumanTaskRepository repository;
    private final AuditService audit;

    public TaskLifecycleService(HumanTaskService tasks, HumanTaskRepository repository, AuditService audit) {
        this.tasks = tasks;
        this.repository = repository;
        this.audit = audit;
    }

    /**
     * Pauses every active task of an instance, remembering the status each held so resume can restore it.
     *
     * @param executionId the instance
     * @param actor       who paused the instance
     * @return how many tasks were paused
     */
    public int pauseTasksFor(String executionId, String actor) {
        List<HumanTask> active = repository.findByWorkflowExecutionIdAndStatusIn(executionId, PAUSABLE);
        for (HumanTask task : active) {
            TaskStatus from = task.getStatus();
            task.setPreviousStatus(from);
            task.setStatus(TaskStatus.PAUSED);
            tasks.saveWithHistory(task, TaskAction.PAUSED, actor, null,
                    "Workflow instance paused", null);
            audit.record(actor, "TASK_PAUSED", "TASK", task.getId(), "OK",
                    details(executionId, task, from, TaskStatus.PAUSED, null));
        }
        logCascade("Paused", active.size(), executionId);
        return active.size();
    }

    /**
     * Restores every paused task of an instance to the status it held before the pause.
     *
     * @param executionId the instance
     * @param actor       who resumed the instance
     * @return how many tasks were restored
     */
    public int resumeTasksFor(String executionId, String actor) {
        List<HumanTask> paused = repository.findByWorkflowExecutionIdAndStatusIn(executionId,
                EnumSet.of(TaskStatus.PAUSED));
        for (HumanTask task : paused) {
            // A paused task always recorded where it came from; fall back to ASSIGNED only if that were ever
            // absent, because a resumed task must be actionable, never left dangling.
            TaskStatus restored = task.getPreviousStatus() == null ? TaskStatus.ASSIGNED
                    : task.getPreviousStatus();
            task.setStatus(restored);
            task.setPreviousStatus(null);
            tasks.saveWithHistory(task, TaskAction.RESUMED, actor, null,
                    "Workflow instance resumed", null);
            audit.record(actor, "TASK_RESUMED", "TASK", task.getId(), "OK",
                    details(executionId, task, TaskStatus.PAUSED, restored, null));
        }
        logCascade("Resumed", paused.size(), executionId);
        return paused.size();
    }

    /**
     * Terminates every still-live task of an instance, paused ones included.
     *
     * @param executionId the instance
     * @param actor       who terminated the instance
     * @param reason      why, recorded on each task and in the audit trail
     * @return how many tasks were terminated
     */
    public int terminateTasksFor(String executionId, String actor, String reason) {
        List<HumanTask> live = repository.findByWorkflowExecutionIdAndStatusIn(executionId, TERMINABLE);
        for (HumanTask task : live) {
            TaskStatus from = task.getStatus();
            task.setStatus(TaskStatus.TERMINATED);
            task.setPreviousStatus(null);
            task.setCancelReason(reason);
            task.setCompletedAt(Instant.now());
            tasks.saveWithHistory(task, TaskAction.TERMINATED, actor, null, reason, null);
            audit.record(actor, "TASK_TERMINATED", "TASK", task.getId(), "OK",
                    details(executionId, task, from, TaskStatus.TERMINATED, reason));
        }
        logCascade("Terminated", live.size(), executionId);
        return live.size();
    }

    private static Map<String, Object> details(String executionId, HumanTask task, TaskStatus from,
                                               TaskStatus to, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("workflowInstanceId", executionId);
        details.put("nodeId", task.getNodeId());
        details.put("previousStatus", from == null ? null : from.name());
        details.put("newStatus", to.name());
        if (reason != null) {
            details.put("reason", reason);
        }
        return details;
    }

    private void logCascade(String verb, int count, String executionId) {
        if (count > 0) {
            log.info("{} {} task(s) of workflow instance {}", verb, count, executionId);
        }
    }
}
