package com.orchpilot.workflow.task;

import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.exception.WorkflowNotFoundException;
import com.orchpilot.workflow.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Raising tasks and moving them between people.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Completing and cancelling, which live in {@link TaskCompletionService}. Those two resume or fail the
 * execution, so they need the execution service, which needs the engine, which needs the node registry, which
 * needs the form node executor — which needs this class to raise the task in the first place. Splitting on
 * "does this touch the execution" cuts that cycle at its only sensible point and does not need {@code @Lazy} to
 * paper over it.
 *
 * <h2>Idempotent creation</h2>
 *
 * <p>{@link #createOrReuse} may be called more than once for the same wait: a node retried, an execution resumed
 * after a crash, another instance in the cluster picking up the same execution. Each of those re-enters the
 * executor. Returning the existing task rather than raising a second one is what stops one approval appearing
 * three times in somebody's inbox, and the unique index makes it hold under a genuine race rather than only
 * under a check.
 */
@Service
public class HumanTaskService {

    private static final Logger log = LoggerFactory.getLogger(HumanTaskService.class);

    /** The two statuses somebody can still act on. */
    static final Set<TaskStatus> ACTIONABLE = EnumSet.of(TaskStatus.OPEN, TaskStatus.ASSIGNED);

    /**
     * Default inbox order: most urgent first, then oldest.
     *
     * <p>Oldest before newest is not an arbitrary choice. An inbox sorted newest-first quietly buries whatever
     * nobody wanted to do, which is exactly the work that needs surfacing.
     *
     * <p>Sorts on {@code priorityWeight} rather than {@code priority}, because the enum persists as its name and
     * a descending sort on a name yields URGENT, NORMAL, LOW, HIGH.
     *
     * <p>{@code dueAt} is deliberately not in the sort. MongoDB orders missing values first when ascending, so
     * including it would float every task with no deadline above the ones that have one — the opposite of the
     * intent. Overdue tasks are flagged in the response and the client sorts on that.
     */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("priorityWeight"),
            Sort.Order.asc("createdAt"));

    private final HumanTaskRepository tasks;
    private final TaskHistoryRepository history;
    private final TaskAuthorizationService authorization;
    private final UserRepository users;
    private final TaskNotifier notifier;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public HumanTaskService(HumanTaskRepository tasks, TaskHistoryRepository history,
                           TaskAuthorizationService authorization, UserRepository users,
                           TaskNotifier notifier, AuditService audit, ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.history = history;
        this.authorization = authorization;
        this.users = users;
        this.notifier = notifier;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------- creation

    /**
     * Raises the task for a parked node, or returns the one already raised.
     *
     * @param request what to raise
     * @return the task, new or existing
     */
    public HumanTask createOrReuse(TaskCreationRequest request) {
        Optional<HumanTask> existing = actionableFor(request.executionId(), request.nodeId());
        if (existing.isPresent()) {
            log.debug("Reusing task {} for execution {} node {}", existing.get().getId(),
                    request.executionId(), request.nodeId());
            return existing.get();
        }

        int attempt = nextAttempt(request.executionId(), request.nodeId());
        HumanTask task = build(request, attempt);
        try {
            task = tasks.save(task);
        } catch (DuplicateKeyException ex) {
            /*
             * Another instance raised the same task between the check and the insert. The unique index on
             * (executionId, nodeId, attempt) refused the duplicate, which is the outcome we want; the loser
             * adopts the winner's task rather than failing the node.
             */
            log.info("Lost the race to create the task for execution {} node {}; adopting the existing one",
                    request.executionId(), request.nodeId());
            return actionableFor(request.executionId(), request.nodeId())
                    .orElseThrow(() -> ex);
        }

        record(task, TaskAction.CREATED, "system", null, null, creationDetails(request));
        notifier.notifyAssigned(task, "New task raised by " + request.workflowName());
        events.publishEvent(HumanTaskEvent.of(task, TaskAction.CREATED, request.createdBy()));
        audit.record(request.createdBy() == null ? "system" : request.createdBy(), "TASK_CREATED", "TASK",
                task.getId(), "OK", Map.of(
                        "executionId", request.executionId(),
                        "nodeId", request.nodeId(),
                        "assigned", task.getAssigneeUserId() != null,
                        "candidateGroups", task.getCandidateGroupIds().size()));

        if (!request.assignment().problems().isEmpty()) {
            log.warn("Task {} was raised with {} assignment problem(s): {}", task.getId(),
                    request.assignment().problems().size(), request.assignment().problems());
        }
        log.info("Raised task {} ({}) for execution {} node {}, status {}", task.getId(), task.getTaskName(),
                request.executionId(), request.nodeId(), task.getStatus());
        return task;
    }

    // -------------------------------------------------------------------- reading

    /**
     * @param taskId the task
     * @return it, or 404
     */
    public HumanTask require(String taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new WorkflowNotFoundException("No task with id '" + taskId + "'"));
    }

    /**
     * Loads a task the caller is entitled to see.
     *
     * <p>Answers 404 rather than 403 for a task that exists but is not theirs. Distinguishing the two would let
     * anyone enumerate task ids and learn which approvals are in flight, which is information even without the
     * contents.
     *
     * @param taskId    the task
     * @param principal the caller
     * @return the task
     */
    public HumanTask requireVisible(String taskId, AuthPrincipal principal) {
        HumanTask task = require(taskId);
        if (!authorization.canView(principal, task)) {
            log.info("User {} was refused a view of task {}", principal == null ? "anonymous"
                    : principal.getUsername(), taskId);
            throw new WorkflowNotFoundException("No task with id '" + taskId + "'");
        }
        return task;
    }

    /**
     * One bucket of the inbox.
     *
     * @param bucket    which bucket
     * @param principal the caller
     * @param filter    optional workflow id and status filter
     * @param pageable  paging; sorted by urgency when the caller expresses no preference
     * @return the page
     */
    public Page<HumanTask> list(TaskBucket bucket, AuthPrincipal principal, TaskFilter filter,
                                Pageable pageable) {
        if (principal == null) {
            throw new AccessDeniedException("Authentication is required to list tasks");
        }
        Pageable effective = withDefaultSort(pageable);
        Set<TaskStatus> statuses = filter.statuses().isEmpty() ? ACTIONABLE : filter.statuses();

        return switch (bucket) {
            case MINE -> tasks.findByAssigneeUserIdAndStatusIn(principal.getUserId(), statuses, effective);
            case AVAILABLE -> tasks.findClaimable(TaskStatus.OPEN,
                    authorization.groupsOf(principal.getUserId()), principal.getUserId(), effective);
            case ALL -> {
                if (!authorization.canViewAll(principal)) {
                    throw new AccessDeniedException("Listing every task requires TASK_VIEW_ALL");
                }
                yield filter.workflowId() == null
                        ? tasks.findByStatusIn(statuses, effective)
                        : tasks.findByWorkflowIdAndStatusIn(filter.workflowId(), statuses, effective);
            }
        };
    }

    /**
     * Counts for the inbox's bucket tabs.
     *
     * @param principal the caller
     * @return counts keyed by bucket name, plus {@code overdue} for the caller's own overdue tasks
     */
    public Map<String, Long> counts(AuthPrincipal principal) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (principal == null) {
            return counts;
        }
        String userId = principal.getUserId();
        counts.put("mine", tasks.countByAssigneeUserIdAndStatusIn(userId, ACTIONABLE));
        counts.put("available", tasks.countClaimable(TaskStatus.OPEN, authorization.groupsOf(userId), userId));
        if (authorization.canViewAll(principal)) {
            counts.put("all", tasks.countByStatusIn(ACTIONABLE));
        }
        counts.put("overdue", tasks.findByStatusInAndDueAtBefore(ACTIONABLE, Instant.now()).stream()
                .filter(task -> task.isAssignedTo(userId))
                .count());
        return counts;
    }

    /**
     * @param taskId the task
     * @return its history, oldest first
     */
    public List<TaskHistoryEntry> historyOf(String taskId) {
        return history.findByTaskIdOrderByAtAsc(taskId);
    }

    /** Every task raised by one execution, for the execution detail screen. */
    public List<HumanTask> forExecution(String executionId) {
        return tasks.findByWorkflowExecutionId(executionId);
    }

    /** The open task on an execution, if there is one. Used by the completion service. */
    public Optional<HumanTask> actionableFor(String executionId, String nodeId) {
        return tasks.findByWorkflowExecutionIdAndNodeIdOrderByAttemptDesc(executionId, nodeId).stream()
                .filter(task -> task.getStatus().isActionable())
                .findFirst();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Takes an open task.
     *
     * @param taskId    the task
     * @param principal the caller
     * @return the claimed task
     */
    public HumanTask claim(String taskId, AuthPrincipal principal) {
        HumanTask task = requireVisible(taskId, principal);
        if (task.getStatus() == TaskStatus.ASSIGNED) {
            // Worth its own message: "somebody got there first" is actionable, "cannot claim" is not.
            throw OperationNotAllowedException.conflict(task.isAssignedTo(principal.getUserId())
                    ? "You have already claimed this task."
                    : "Somebody else claimed this task first.");
        }
        if (!task.getStatus().isActionable()) {
            throw OperationNotAllowedException.conflict("This task is " + task.getStatus().name().toLowerCase(
                    Locale.ROOT) + " and can no longer be claimed.");
        }
        if (!authorization.canClaim(principal, task)) {
            throw new AccessDeniedException("This task was not offered to you");
        }

        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssigneeUserId(principal.getUserId());
        task.setAssigneeUsername(principal.getUsername());
        task.setClaimedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        HumanTask saved = tasks.save(task);

        record(saved, TaskAction.CLAIMED, principal.getUsername(), principal.getUserId(), null, null);
        events.publishEvent(HumanTaskEvent.of(saved, TaskAction.CLAIMED, principal.getUsername()));
        audit.record(principal.getUsername(), "TASK_CLAIMED", "TASK", taskId, "OK", null);
        return saved;
    }

    /**
     * Gives an assigned task back to its candidates.
     *
     * @param taskId    the task
     * @param principal the caller
     * @return the released task
     */
    public HumanTask release(String taskId, AuthPrincipal principal) {
        HumanTask task = requireVisible(taskId, principal);
        if (task.getStatus() != TaskStatus.ASSIGNED) {
            throw OperationNotAllowedException.conflict("Only an assigned task can be released.");
        }
        if (!task.hasCandidates() && !authorization.canViewAll(principal)) {
            throw OperationNotAllowedException.conflict(
                    "This task names no candidates, so releasing it would leave it visible to nobody. "
                            + "Ask an administrator to reassign it instead.");
        }
        if (!authorization.canRelease(principal, task)) {
            throw new AccessDeniedException("This task is not yours to release");
        }

        String previousAssignee = task.getAssigneeUsername();
        task.setStatus(TaskStatus.OPEN);
        task.setAssigneeUserId(null);
        task.setAssigneeUsername(null);
        task.setClaimedAt(null);
        /*
         * The draft goes with the assignment. A half-filled form is the previous holder's working notes, and
         * handing it to whoever claims the task next both leaks what they wrote and misleads the new holder
         * about what the workflow supplied.
         */
        task.setDraftData(null);
        task.setDraftSavedAt(null);
        task.setUpdatedAt(Instant.now());
        HumanTask saved = tasks.save(task);

        record(saved, TaskAction.RELEASED, principal.getUsername(), principal.getUserId(), null,
                Map.of("previousAssignee", String.valueOf(previousAssignee)));
        notifier.notifyAssigned(saved, "Released by " + principal.getUsername());
        events.publishEvent(HumanTaskEvent.of(saved, TaskAction.RELEASED, principal.getUsername()));
        audit.record(principal.getUsername(), "TASK_RELEASED", "TASK", taskId, "OK", null);
        return saved;
    }

    /**
     * Moves a task to somebody else.
     *
     * @param taskId    the task
     * @param toUser    the new assignee's id or username
     * @param comment   why, recorded in the history
     * @param principal the caller
     * @return the reassigned task
     */
    public HumanTask reassign(String taskId, String toUser, String comment, AuthPrincipal principal) {
        HumanTask task = requireVisible(taskId, principal);
        if (!task.getStatus().isActionable()) {
            throw OperationNotAllowedException.conflict("This task is " + task.getStatus().name()
                    .toLowerCase(Locale.ROOT) + " and can no longer be reassigned.");
        }
        if (!authorization.canReassign(principal, task)) {
            throw new AccessDeniedException("You may not reassign this task");
        }
        User target = resolveUser(toUser);

        String previous = task.getAssigneeUsername();
        task.setStatus(TaskStatus.ASSIGNED);
        task.setAssigneeUserId(target.getId());
        task.setAssigneeUsername(target.getUsername());
        task.setClaimedAt(Instant.now());
        // Same reasoning as release: the draft belonged to whoever was holding it.
        task.setDraftData(null);
        task.setDraftSavedAt(null);
        task.setUpdatedAt(Instant.now());
        HumanTask saved = tasks.save(task);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", previous == null ? "unassigned" : previous);
        details.put("to", target.getUsername());
        record(saved, TaskAction.REASSIGNED, principal.getUsername(), principal.getUserId(), comment, details);
        notifier.notifyAssigned(saved, "Reassigned by " + principal.getUsername());
        events.publishEvent(HumanTaskEvent.of(saved, TaskAction.REASSIGNED, principal.getUsername()));
        audit.record(principal.getUsername(), "TASK_REASSIGNED", "TASK", taskId, "OK", details);
        return saved;
    }

    /**
     * Stores partial input without submitting it.
     *
     * <p>Not validated, which is the point: a draft exists precisely because the form is not yet complete. It is
     * also not mapped to variables, so an unfinished draft cannot influence the workflow.
     *
     * @param taskId    the task
     * @param formData  whatever has been filled in so far
     * @param principal the caller
     * @return the task
     */
    public HumanTask saveDraft(String taskId, Map<String, Object> formData, AuthPrincipal principal) {
        HumanTask task = requireVisible(taskId, principal);
        // Draft-saving stays open while the instance is paused or terminated — a person must not lose form
        // input to an administrative action they did not cause — so it is gated on allowsDraft(), which is
        // broader than isActionable(): only a genuinely finished task (submitted, withdrawn, expired) refuses.
        if (!task.getStatus().allowsDraft()) {
            throw OperationNotAllowedException.conflict("This task is " + task.getStatus().name()
                    .toLowerCase(Locale.ROOT) + " and can no longer be edited.");
        }
        if (!authorization.canSaveDraft(principal, task)) {
            throw new AccessDeniedException("Only the assignee may save a draft of this task");
        }

        task.setDraftData(formData);
        task.setDraftSavedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        HumanTask saved = tasks.save(task);

        // Field names only. The values are the point of a draft and belong nowhere but the task itself.
        record(saved, TaskAction.DRAFT_SAVED, principal.getUsername(), principal.getUserId(), null,
                Map.of("fields", formData == null ? List.of() : new ArrayList<>(formData.keySet())));
        return saved;
    }

    /**
     * Saves a draft for an external form task, authorised by a form token rather than a principal.
     *
     * <p>The public counterpart of {@link #saveDraft}. The token has already authorised the caller, so there is
     * no principal to check; the one gate is that the task still {@code allowsDraft()} — which stays true while
     * the instance is paused or terminated, exactly so a customer never loses input to an administrative action.
     * Saving a draft advances nothing: no variable is written and no node runs.
     *
     * @param taskId     the external form task
     * @param formData   the partial values
     * @param actorLabel a non-personal label for the history, e.g. {@code external}
     * @return the task with its draft stored
     */
    public HumanTask saveDraftExternally(String taskId, Map<String, Object> formData, String actorLabel) {
        HumanTask task = require(taskId);
        if (!task.getStatus().allowsDraft()) {
            throw OperationNotAllowedException.conflict("This form is no longer available for editing.");
        }
        task.setDraftData(formData);
        task.setDraftSavedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        HumanTask saved = tasks.save(task);
        // Field names only, never the values a customer entered.
        record(saved, TaskAction.DRAFT_SAVED, actorLabel, null, null,
                Map.of("fields", formData == null ? List.of() : new ArrayList<>(formData.keySet()),
                        "channel", "EXTERNAL"));
        return saved;
    }

    // ---------------------------------------------------------- used by the completion service

    /**
     * Persists a task and appends a history entry, for the completion service.
     *
     * @param task    the task to save
     * @param action  what happened
     * @param actor   username
     * @param userId  their id, or null
     * @param comment optional note
     * @param details structured context; field names, never values
     * @return the saved task
     */
    HumanTask saveWithHistory(HumanTask task, TaskAction action, String actor, String userId, String comment,
                              Map<String, Object> details) {
        task.setUpdatedAt(Instant.now());
        HumanTask saved = tasks.save(task);
        record(saved, action, actor, userId, comment, details);
        events.publishEvent(HumanTaskEvent.of(saved, action, actor));
        return saved;
    }

    TaskAuthorizationService authorization() {
        return authorization;
    }

    TaskNotifier notifier() {
        return notifier;
    }

    HumanTaskRepository repository() {
        return tasks;
    }

    // ------------------------------------------------------------------- internals

    private HumanTask build(TaskCreationRequest request, int attempt) {
        TaskAssignment assignment = request.assignment();
        HumanTask task = new HumanTask();
        task.setWorkflowExecutionId(request.executionId());
        task.setWorkflowId(request.workflowId());
        task.setWorkflowVersion(request.workflowVersion());
        task.setWorkflowName(request.workflowName());
        task.setNodeId(request.nodeId());
        task.setNodeName(request.nodeName());
        task.setAttempt(attempt);
        task.setFormDefinitionId(request.formDefinitionId());
        task.setFormVersion(request.formVersion());
        task.setPrefill(request.prefill());
        task.setTaskName(request.taskName());
        task.setDescription(request.description());
        task.setStatus(assignment.initialStatus());
        task.setExternal(assignment.external());
        task.setPriority(assignment.priority());
        task.setAssigneeUserId(assignment.assigneeUserId());
        task.setAssigneeUsername(assignment.assigneeUsername());
        task.setCandidateUserIds(assignment.candidateUserIds());
        task.setCandidateGroupIds(assignment.candidateGroupIds());
        task.setCreatedBy(request.createdBy());
        task.setCorrelationId(request.correlationId());

        Instant now = Instant.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        if (assignment.isDirectlyAssigned()) {
            // Directly assigned means claimed on arrival, so the "how long has somebody held this" question
            // has an answer from the start.
            task.setClaimedAt(now);
        }
        if (assignment.dueIn() != null) {
            task.setDueAt(now.plus(assignment.dueIn()));
        }
        if (assignment.expiresIn() != null) {
            task.setExpiresAt(now.plus(assignment.expiresIn()));
        }
        return task;
    }

    private int nextAttempt(String executionId, String nodeId) {
        return tasks.findByWorkflowExecutionIdAndNodeIdOrderByAttemptDesc(executionId, nodeId).stream()
                .mapToInt(HumanTask::getAttempt)
                .max()
                .orElse(0) + 1;
    }

    private Map<String, Object> creationDetails(TaskCreationRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nodeId", request.nodeId());
        details.put("formDefinitionId", request.formDefinitionId());
        details.put("formVersion", request.formVersion());
        if (!request.assignment().problems().isEmpty()) {
            // Recorded on the task itself so whoever eventually finds an unaddressed task can see why it is
            // unaddressed without reading server logs.
            details.put("assignmentProblems", request.assignment().problems());
        }
        return details;
    }

    private User resolveUser(String idOrUsername) {
        if (idOrUsername == null || idOrUsername.isBlank()) {
            throw new IllegalArgumentException("A new assignee is required");
        }
        String raw = idOrUsername.trim();
        Optional<User> byId = users.findById(raw);
        Optional<User> found = byId.isPresent()
                ? byId
                : users.findByUsername(raw.toLowerCase(Locale.ROOT));
        return found.filter(User::isUsable).orElseThrow(() -> OperationNotAllowedException.conflict(
                "'" + raw + "' is not an enabled account, so the task cannot be assigned to it."));
    }

    private void record(HumanTask task, TaskAction action, String actor, String userId, String comment,
                        Map<String, Object> details) {
        try {
            history.save(TaskHistoryEntry.of(task, action, actor, userId, comment, details));
        } catch (RuntimeException ex) {
            // The trail is important but not more important than the operation it describes. Losing an entry
            // must be loud and must not roll back a claim somebody just made.
            log.error("Could not write task history for {} ({}): {}", task.getId(), action, ex.getMessage());
        }
    }

    private static Pageable withDefaultSort(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, 20, DEFAULT_SORT);
        }
        return pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
    }
}
