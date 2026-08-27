package com.orchpilot.workflow.task;

import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.dto.FormSubmissionRequest;
import com.orchpilot.workflow.exception.WorkflowInstanceStateException;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The two task operations that move the execution: completing and cancelling.
 *
 * <p>Separated from {@link HumanTaskService} to cut a bean cycle. This class needs {@link ExecutionService},
 * which needs the engine, which needs the node registry, which needs the form node executor, which needs the
 * service that raises tasks. Putting "raise and hand around" in one bean and "resume or fail" in another breaks
 * the loop at the only place it has a natural seam, and does not need {@code @Lazy} to hide the problem.
 *
 * <h2>The order of writes when a task is completed</h2>
 *
 * <p>The task is marked COMPLETED and saved <em>before</em> the execution is resumed, and that order is
 * deliberate:
 *
 * <ul>
 *   <li>It is the arbitration point. Two people submitting the same task at the same time both read an ASSIGNED
 *       task; the optimistic-locking version means one save wins and the other gets a 409 instead of both
 *       resuming the execution and running the next node twice.</li>
 *   <li>The human decision is the part that cannot be reconstructed. If the process dies between the two
 *       writes, a stored approval with a stalled workflow is recoverable — {@link #retryResume} exists for
 *       exactly that — whereas a resumed workflow with no record of who approved it is not.</li>
 * </ul>
 */
@Service
public class TaskCompletionService {

    private static final Logger log = LoggerFactory.getLogger(TaskCompletionService.class);

    private final HumanTaskService tasks;
    private final HumanTaskRepository repository;
    private final TaskAuthorizationService authorization;
    private final FormNodeBinding binding;
    private final ExecutionService executions;
    private final TaskNotifier notifier;
    private final AuditService audit;

    public TaskCompletionService(HumanTaskService tasks, HumanTaskRepository repository,
                                TaskAuthorizationService authorization, FormNodeBinding binding,
                                ExecutionService executions, TaskNotifier notifier, AuditService audit) {
        this.tasks = tasks;
        this.repository = repository;
        this.authorization = authorization;
        this.binding = binding;
        this.executions = executions;
        this.notifier = notifier;
        this.audit = audit;
    }

    /**
     * Submits a task and resumes its execution.
     *
     * @param taskId    the task
     * @param formData  the submitted values, keyed by field name
     * @param principal the caller, who must be the assignee
     * @return the completed task
     * @throws com.orchpilot.workflow.exception.FormSubmissionInvalidException when the form rejects the values
     */
    public HumanTask complete(String taskId, Map<String, Object> formData, AuthPrincipal principal) {
        HumanTask task = tasks.requireVisible(taskId, principal);

        /*
         * The instance-state gate, checked before anything else and enforced here on the server rather than
         * only in the UI. A paused or terminated instance must never accept a submission — that is what keeps a
         * disabled Submit button from being the only thing standing between a stale client and a workflow that
         * should not advance. Read the instance's current status; a form belongs to exactly one instance, found
         * by the task's executionId. Other terminal states (cancelled, completed, failed) are left to the task's
         * own actionable check below, which reports them accurately.
         */
        WorkflowExecution instance = executions.get(task.getWorkflowExecutionId());
        if (instance.getStatus() == com.orchpilot.workflow.model.ExecutionStatus.PAUSED) {
            recordSubmitRejected(task, principal, "WORKFLOW_INSTANCE_PAUSED");
            throw WorkflowInstanceStateException.paused();
        }
        if (instance.getStatus() == com.orchpilot.workflow.model.ExecutionStatus.TERMINATED) {
            recordSubmitRejected(task, principal, "WORKFLOW_INSTANCE_TERMINATED");
            throw WorkflowInstanceStateException.terminated();
        }

        if (!task.getStatus().isActionable()) {
            throw OperationNotAllowedException.conflict("This task is already "
                    + task.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (task.getStatus() == TaskStatus.OPEN) {
            throw OperationNotAllowedException.conflict(
                    "Claim this task before submitting it, so the record shows who did the work.");
        }
        if (!authorization.canComplete(principal, task)) {
            /*
             * Reached by an administrator submitting somebody else's task, which is refused on purpose: writing
             * another person's name against an approval they never gave makes the whole record worthless.
             *
             * Thrown as a 403 that keeps its message, rather than as an AccessDeniedException, which the global
             * handler flattens to "You do not have permission to perform this action". That flattening is right
             * for an authorization failure in general — the reason is often something the caller should not be
             * told — but here the intent behind the attempt is usually legitimate and the answer is "reassign it
             * first", which they cannot guess. Nothing is disclosed: reaching this line means the caller can
             * already read the task, and the assignee's name is on it.
             */
            throw OperationNotAllowedException.forbidden(task.getAssigneeUsername() == null
                    ? "This task has no assignee, so there is nobody whose submission it would be. Claim or "
                            + "reassign it first."
                    : "Only " + task.getAssigneeUsername() + " may submit this task. Reassign it to yourself "
                            + "first if you need to complete it, which leaves a record that you did.");
        }
        if (task.getExpiresAt() != null && task.getExpiresAt().isBefore(Instant.now())) {
            throw OperationNotAllowedException.conflict("This task expired at " + task.getExpiresAt()
                    + " and can no longer be submitted.");
        }

        Map<String, Object> submitted = formData == null ? Map.of() : formData;

        // The authoritative version, loaded from MongoDB by id and pinned version. Nothing about the shape of
        // the form is taken from the request.
        Optional<FormVersion> form = formOf(task);
        form.ifPresent(version -> binding.validateOrThrow(version, submitted));

        task.setStatus(TaskStatus.COMPLETED);
        task.setSubmittedData(submitted);
        task.setCompletedAt(Instant.now());
        task.setCompletedByUserId(principal.getUserId());
        task.setCompletedByUsername(principal.getUsername());
        task.setDraftData(null);
        task.setDraftSavedAt(null);

        Map<String, Object> details = new LinkedHashMap<>();
        // Field names only, here and in the audit record. The values are on the task, under the task's own
        // authorization, and an audit log is read by more people than a task is.
        details.put("fields", new ArrayList<>(submitted.keySet()));
        details.put("formVersion", task.getFormVersion());
        HumanTask saved = tasks.saveWithHistory(task, TaskAction.COMPLETED, principal.getUsername(),
                principal.getUserId(), null, details);

        audit.record(principal.getUsername(), "TASK_COMPLETED", "TASK", taskId, "OK", Map.of(
                "executionId", saved.getWorkflowExecutionId(),
                "nodeId", saved.getNodeId(),
                "fields", new ArrayList<>(submitted.keySet())));

        resume(saved, submitted, principal.getUsername());
        return saved;
    }

    /**
     * Completes an external form task and resumes its workflow, authorised by a form token rather than a
     * principal.
     *
     * <p>The counterpart of {@link #complete} for the public form path. The token has already authorised the
     * caller and the {@code ExternalFormService} has already checked the workflow instance is running, so this
     * does not re-run the principal, assignee or instance-state checks; it re-loads the task and performs the
     * one check that arbitrates the terminate-versus-submit race — whether the task is still actionable — then
     * validates the form on the server, records the submission and resumes the engine through the same
     * {@code submitSignal} path an internal completion uses. A task the instance's termination has already moved
     * to {@code TERMINATED} fails the actionable check here, so a submission never lands on a dead instance.
     *
     * @param taskId     the external form task
     * @param formData   the submitted values
     * @param actorLabel a non-personal label for the audit trail, e.g. {@code external} or a customer reference
     * @return the completed task
     * @throws OperationNotAllowedException when the task is no longer actionable (already submitted, terminated)
     */
    public HumanTask completeExternally(String taskId, Map<String, Object> formData, String actorLabel) {
        HumanTask task = tasks.require(taskId);
        if (!task.getStatus().isActionable()) {
            throw OperationNotAllowedException.conflict("This form is no longer available for submission.");
        }

        Map<String, Object> submitted = formData == null ? Map.of() : formData;
        Optional<FormVersion> form = formOf(task);
        form.ifPresent(version -> binding.validateOrThrow(version, submitted));

        task.setStatus(TaskStatus.COMPLETED);
        task.setSubmittedData(submitted);
        task.setCompletedAt(Instant.now());
        task.setCompletedByUsername(actorLabel);
        task.setDraftData(null);
        task.setDraftSavedAt(null);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", new ArrayList<>(submitted.keySet()));
        details.put("formVersion", task.getFormVersion());
        details.put("channel", "EXTERNAL");
        HumanTask saved = tasks.saveWithHistory(task, TaskAction.COMPLETED, actorLabel, null, null, details);

        audit.record(actorLabel, "TASK_COMPLETED", "TASK", taskId, "OK", Map.of(
                "executionId", saved.getWorkflowExecutionId(),
                "nodeId", saved.getNodeId(),
                "channel", "EXTERNAL",
                "fields", new ArrayList<>(submitted.keySet())));

        resume(saved, submitted, actorLabel);
        return saved;
    }

    /**
     * Resumes an execution whose task was recorded but whose workflow did not continue.
     *
     * <p>The recovery path for the gap the write order leaves open. Needs {@code TASK_ADMIN}, checked by the
     * controller, and is safe to call repeatedly: an execution that is no longer waiting refuses the submission.
     *
     * @param taskId    the completed task
     * @param principal the caller
     * @return the execution's status after the attempt
     */
    public String retryResume(String taskId, AuthPrincipal principal) {
        HumanTask task = tasks.require(taskId);
        if (task.getStatus() != TaskStatus.COMPLETED) {
            throw OperationNotAllowedException.conflict(
                    "Only a completed task can be re-submitted to its execution.");
        }
        WorkflowExecution execution = executions.get(task.getWorkflowExecutionId());
        audit.record(principal.getUsername(), "TASK_RESUME_RETRIED", "TASK", taskId, "OK",
                Map.of("executionId", task.getWorkflowExecutionId(), "status", execution.getStatus().name()));
        resume(task, task.getSubmittedData(), principal.getUsername());
        return executions.get(task.getWorkflowExecutionId()).getStatus().name();
    }

    /**
     * Withdraws a task, which cancels the execution it belongs to.
     *
     * <p>Cancelling the execution rather than skipping the node is the honest outcome. The step exists because a
     * person has to decide something; if nobody will, the run cannot proceed, and inventing a default answer on
     * their behalf is worse than stopping.
     *
     * @param taskId    the task
     * @param reason    why, shown in the history and the audit record
     * @param principal the caller
     * @return the cancelled task
     */
    public HumanTask cancel(String taskId, String reason, AuthPrincipal principal) {
        HumanTask task = tasks.require(taskId);
        if (!task.getStatus().isActionable()) {
            throw OperationNotAllowedException.conflict("This task is already "
                    + task.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        if (!authorization.canCancel(principal, task)) {
            throw new AccessDeniedException("You may not cancel this task");
        }

        task.setStatus(TaskStatus.CANCELLED);
        task.setCancelReason(reason);
        task.setCompletedAt(Instant.now());
        HumanTask saved = tasks.saveWithHistory(task, TaskAction.CANCELLED, principal.getUsername(),
                principal.getUserId(), reason, null);

        audit.record(principal.getUsername(), "TASK_CANCELLED", "TASK", taskId, "OK",
                Map.of("executionId", saved.getWorkflowExecutionId(), "reason", String.valueOf(reason)));

        cancelExecution(saved, "Task cancelled by " + principal.getUsername());
        return saved;
    }

    /**
     * Expires a task whose hard deadline has passed, called by the scheduler.
     *
     * <p>No authorization check: there is no caller to authorise. The scheduler is the only path here, and it
     * acts on a deadline the workflow author set.
     *
     * @param task the overdue task
     */
    public void expire(HumanTask task) {
        if (!task.getStatus().isActionable()) {
            return;
        }
        task.setStatus(TaskStatus.EXPIRED);
        task.setCompletedAt(Instant.now());
        HumanTask saved = tasks.saveWithHistory(task, TaskAction.EXPIRED, "system", null,
                "The task passed its expiry without being submitted", Map.of("expiresAt",
                        String.valueOf(task.getExpiresAt())));

        audit.record("system", "TASK_EXPIRED", "TASK", saved.getId(), "OK",
                Map.of("executionId", saved.getWorkflowExecutionId()));
        notifier.notifyExpired(saved);
        cancelExecution(saved, "Task " + saved.getId() + " expired at " + saved.getExpiresAt());
    }

    /**
     * Closes every open task on an execution that has ended some other way.
     *
     * <p>Without this, cancelling an execution leaves its approval sitting in somebody's inbox for a workflow
     * that is already dead, and completing it would fail confusingly.
     *
     * @param executionId the execution
     * @param reason      what happened to it
     * @return how many tasks were closed
     */
    public int cancelTasksFor(String executionId, String reason) {
        List<HumanTask> open = repository.findByWorkflowExecutionIdAndStatusIn(executionId,
                HumanTaskService.ACTIONABLE);
        for (HumanTask task : open) {
            task.setStatus(TaskStatus.CANCELLED);
            task.setCancelReason(reason);
            task.setCompletedAt(Instant.now());
            tasks.saveWithHistory(task, TaskAction.CANCELLED, "system", null, reason, null);
        }
        if (!open.isEmpty()) {
            log.info("Closed {} outstanding task(s) on execution {}: {}", open.size(), executionId, reason);
        }
        return open.size();
    }

    // ------------------------------------------------------------------- internals

    /**
     * Records a rejected submit in the audit trail. Field names and status only — never the form values the
     * user was trying to submit, which do not belong in a log read by more people than the task is.
     */
    private void recordSubmitRejected(HumanTask task, AuthPrincipal principal, String errorCode) {
        audit.record(principal.getUsername(), "FORM_SUBMIT_REJECTED", "TASK", task.getId(), "DENIED", Map.of(
                "workflowInstanceId", task.getWorkflowExecutionId(),
                "nodeId", String.valueOf(task.getNodeId()),
                "errorCode", errorCode));
    }

    private Optional<FormVersion> formOf(HumanTask task) {
        if (task.getFormDefinitionId() == null) {
            return Optional.empty();
        }
        return binding.resolve(task.getFormDefinitionId(),
                task.getFormVersion() > 0 ? task.getFormVersion() : null);
    }

    /**
     * Hands the submission to the engine.
     *
     * <p>Synchronous, so the caller learns whether the next node failed rather than being told "submitted" and
     * discovering later that the workflow stopped. The mapping from field to variable happens inside the node
     * executor, using the same authoritative version validated above.
     */
    private void resume(HumanTask task, Map<String, Object> submitted, String actor) {
        FormSubmissionRequest request = new FormSubmissionRequest(task.getNodeId(),
                task.getFormDefinitionId(), submitted, Boolean.FALSE);
        try {
            executions.submitSignal(task.getWorkflowExecutionId(), request, actor);
        } catch (RuntimeException ex) {
            /*
             * The decision is already recorded, so this is a stalled workflow rather than lost work. Logged at
             * error and surfaced to the caller, because somebody has to know the run did not continue.
             */
            log.error("Task {} was completed but execution {} could not be resumed: {}", task.getId(),
                    task.getWorkflowExecutionId(), ex.getMessage(), ex);
            throw OperationNotAllowedException.conflict(
                    "Your submission was recorded, but the workflow could not continue: " + ex.getMessage()
                            + " An administrator can retry it from the task.");
        }
    }

    private void cancelExecution(HumanTask task, String reason) {
        try {
            WorkflowExecution execution = executions.get(task.getWorkflowExecutionId());
            if (execution.getStatus().isTerminal()) {
                return;
            }
            executions.cancel(task.getWorkflowExecutionId(), "system");
            log.info("Cancelled execution {} because {}", task.getWorkflowExecutionId(), reason);
        } catch (RuntimeException ex) {
            // The task is already closed. An execution that could not be cancelled is a stuck run, not lost
            // data, and must not turn a successful cancellation into a 500.
            log.error("Closed task {} but could not cancel execution {}: {}", task.getId(),
                    task.getWorkflowExecutionId(), ex.getMessage());
        }
    }
}
