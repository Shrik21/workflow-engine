package com.orchpilot.workflow.task;

import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.task.dto.TaskRequests;
import com.orchpilot.workflow.task.dto.TaskResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The task inbox API.
 *
 * <h2>Identity comes from the token, never from the request</h2>
 *
 * <p>No endpoint here accepts a user id. The task is named in the path, the person is the authenticated principal,
 * and the two are compared server-side. Changing the id in the URL to somebody else's task returns 404, and there
 * is no field a client could set to claim to be them: {@code POST /api/tasks/{id}/complete} takes form values and
 * nothing else.
 *
 * <h2>Two layers of authorization, deliberately</h2>
 *
 * <p>{@code @PreAuthorize} asserts the system permission — may this account use the task feature at all — and the
 * service asserts the per-task rule: is this task theirs. Neither is sufficient alone. The annotation cannot know
 * about assignment, and a service check without the annotation would let an account with no task permissions
 * reach the code that decides.
 */
@RestController
@RequestMapping("/api/tasks")
@Tag(name = "Tasks", description = "Human tasks: the work a form node raises for a person")
public class TaskController {

    private final HumanTaskService tasks;
    private final TaskCompletionService completion;
    private final TaskAuthorizationService authorization;
    private final FormNodeBinding forms;

    public TaskController(HumanTaskService tasks, TaskCompletionService completion,
                          TaskAuthorizationService authorization, FormNodeBinding forms) {
        this.tasks = tasks;
        this.completion = completion;
        this.authorization = authorization;
        this.forms = forms;
    }

    @PreAuthorize("hasAuthority('TASK_VIEW') or hasAuthority('TASK_VIEW_ALL')")
    @GetMapping
    @Operation(summary = "List tasks in one bucket",
            description = "bucket=mine returns tasks assigned to you, bucket=available returns open tasks you "
                    + "could claim, and bucket=all returns everything and requires TASK_VIEW_ALL. Rows carry no "
                    + "form values; read one task to get those.")
    public Page<TaskResponses.Summary> list(
            @Parameter(description = "mine, available or all") @RequestParam(defaultValue = "mine") String bucket,
            @RequestParam(required = false) String workflowId,
            @Parameter(description = "Repeatable, or comma-separated: OPEN, ASSIGNED, COMPLETED, CANCELLED, "
                    + "EXPIRED. Defaults to the actionable ones.")
            @RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuthPrincipal principal = principal();
        TaskBucket parsed = TaskBucket.parseOrMine(bucket);
        Page<HumanTask> found = tasks.list(parsed, principal, TaskFilter.of(workflowId, status),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)));
        return found.map(task -> TaskResponses.Summary.of(task, principal.getUserId(),
                authorization.canClaim(principal, task)));
    }

    @PreAuthorize("hasAuthority('TASK_VIEW') or hasAuthority('TASK_VIEW_ALL')")
    @GetMapping("/counts")
    @Operation(summary = "Bucket counts for the inbox tabs")
    public Map<String, Long> counts() {
        return tasks.counts(principal());
    }

    @PreAuthorize("hasAuthority('TASK_VIEW') or hasAuthority('TASK_VIEW_ALL')")
    @GetMapping("/{taskId}")
    @Operation(summary = "Get one task, with its form and prefilled values",
            description = "Returns 404 for a task that exists but is not yours. Distinguishing 403 from 404 "
                    + "would let anyone enumerate task ids and learn which approvals are in flight.")
    public TaskResponses.Detail get(@PathVariable String taskId) {
        AuthPrincipal principal = principal();
        HumanTask task = tasks.requireVisible(taskId, principal);
        return detail(task, principal);
    }

    @PreAuthorize("hasAuthority('TASK_VIEW') or hasAuthority('TASK_VIEW_ALL')")
    @GetMapping("/{taskId}/history")
    @Operation(summary = "Get a task's history")
    public List<TaskResponses.HistoryEntry> history(@PathVariable String taskId) {
        // Loaded through the visibility check first, so history is not a side door into a task you cannot read.
        tasks.requireVisible(taskId, principal());
        return tasks.historyOf(taskId).stream().map(TaskResponses.HistoryEntry::of).toList();
    }

    @PreAuthorize("hasAuthority('TASK_CLAIM') or hasAuthority('TASK_ADMIN')")
    @PostMapping("/{taskId}/claim")
    @Operation(summary = "Claim an open task",
            description = "Answers 409 when somebody else got there first, which is the outcome of two people "
                    + "opening the same inbox.")
    public TaskResponses.Detail claim(@PathVariable String taskId) {
        AuthPrincipal principal = principal();
        return detail(tasks.claim(taskId, principal), principal);
    }

    @PreAuthorize("hasAuthority('TASK_CLAIM') or hasAuthority('TASK_ADMIN')")
    @PostMapping("/{taskId}/release")
    @Operation(summary = "Give a claimed task back to its candidates",
            description = "Any draft is discarded: it was the previous holder's working notes.")
    public TaskResponses.Detail release(@PathVariable String taskId) {
        AuthPrincipal principal = principal();
        return detail(tasks.release(taskId, principal), principal);
    }

    @PreAuthorize("hasAuthority('TASK_COMPLETE')")
    @PostMapping("/{taskId}/draft")
    @Operation(summary = "Save partial input without submitting",
            description = "Not validated, and not mapped to workflow variables. An unfinished draft cannot "
                    + "influence the workflow.")
    public TaskResponses.Detail saveDraft(@PathVariable String taskId,
                                          @RequestBody TaskRequests.Submission request) {
        AuthPrincipal principal = principal();
        return detail(tasks.saveDraft(taskId, request.safeData(), principal), principal);
    }

    @PreAuthorize("hasAuthority('TASK_COMPLETE')")
    @PostMapping("/{taskId}/complete")
    @Operation(summary = "Submit a task and resume its workflow",
            description = "Validates against the published form version the task was raised with, maps each "
                    + "field to the workflow variable that form declares, and resumes the execution. Answers 422 "
                    + "listing every field that needs attention. Only the assignee may submit; an administrator "
                    + "who needs to finish somebody's task reassigns it first, which leaves a record.")
    public TaskResponses.Detail complete(@PathVariable String taskId,
                                         @RequestBody TaskRequests.Submission request) {
        AuthPrincipal principal = principal();
        return detail(completion.complete(taskId, request.safeData(), principal), principal);
    }

    @PreAuthorize("hasAuthority('TASK_REASSIGN') or hasAuthority('TASK_ADMIN')")
    @PostMapping("/{taskId}/reassign")
    @Operation(summary = "Move a task to somebody else",
            description = "An administrator may reassign any task; anybody else may hand on only a task that is "
                    + "currently theirs.")
    public TaskResponses.Detail reassign(@PathVariable String taskId,
                                         @Valid @RequestBody TaskRequests.Reassignment request) {
        AuthPrincipal principal = principal();
        HumanTask task = tasks.reassign(taskId, request.assignee(), request.comment(), principal);
        return detail(task, principal);
    }

    @PreAuthorize("hasAuthority('TASK_CANCEL') or hasAuthority('TASK_ADMIN')")
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "Withdraw a task",
            description = "Cancels the execution as well. The step exists because a person has to decide "
                    + "something; if nobody will, the run cannot proceed and inventing an answer would be worse.")
    public TaskResponses.Detail cancel(@PathVariable String taskId,
                                       @Valid @RequestBody(required = false) TaskRequests.Cancellation request) {
        AuthPrincipal principal = principal();
        String reason = request == null ? null : request.reason();
        return detail(completion.cancel(taskId, reason, principal), principal);
    }

    @PreAuthorize("hasAuthority('TASK_ADMIN')")
    @PostMapping("/{taskId}/retry-resume")
    @Operation(summary = "Re-send a completed task's submission to its execution",
            description = "For the rare case where a submission was recorded but the workflow did not continue. "
                    + "Safe to repeat: an execution that is no longer waiting refuses it.")
    public Map<String, String> retryResume(@PathVariable String taskId) {
        return Map.of("executionStatus", completion.retryResume(taskId, principal()));
    }

    // ---------------------------------------------------------------------- helpers

    private TaskResponses.Detail detail(HumanTask task, AuthPrincipal principal) {
        Optional<FormVersion> form = task.getFormDefinitionId() == null
                ? Optional.empty()
                : forms.resolve(task.getFormDefinitionId(),
                        task.getFormVersion() > 0 ? task.getFormVersion() : null);

        String issue = null;
        if (task.getFormDefinitionId() == null) {
            issue = "The workflow node that raised this task references no published form, so there is nothing "
                    + "to fill in. Completing it simply lets the workflow continue.";
        } else if (form.isEmpty()) {
            issue = "Version " + task.getFormVersion() + " of this task's form could not be loaded, so it "
                    + "cannot be rendered. Ask an administrator to look at the form definition.";
        }

        return new TaskResponses.Detail(
                TaskResponses.Summary.of(task, principal.getUserId(),
                        authorization.canClaim(principal, task)),
                form.orElse(null),
                issue,
                TaskResponses.initialDataFor(task),
                // Only once it is finished. Before that there is nothing submitted, and after it the values are
                // the record of what was decided.
                task.getStatus() == TaskStatus.COMPLETED ? task.getSubmittedData() : Map.of(),
                new TaskResponses.Capabilities(
                        authorization.canClaim(principal, task),
                        authorization.canRelease(principal, task),
                        authorization.canComplete(principal, task),
                        authorization.canSaveDraft(principal, task),
                        authorization.canReassign(principal, task),
                        authorization.canCancel(principal, task)),
                tasks.historyOf(task.getId()).stream().map(TaskResponses.HistoryEntry::of).toList());
    }

    /**
     * The authenticated principal.
     *
     * <p>Read from the security context rather than taken as a method parameter, so there is no signature into
     * which a caller-supplied identity could be bound. Absence is a programming error: every endpoint here is
     * behind {@code @PreAuthorize}.
     */
    private AuthPrincipal principal() {
        return CurrentUser.principal().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException(
                        "This endpoint requires an authenticated user"));
    }
}
