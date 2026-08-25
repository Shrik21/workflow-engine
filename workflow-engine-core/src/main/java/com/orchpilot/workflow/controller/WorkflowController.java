package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.dto.ExecuteWorkflowRequest;
import com.orchpilot.workflow.dto.ExecutionResponse;
import com.orchpilot.workflow.dto.ValidationResponse;
import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.dto.WorkflowResponse;
import com.orchpilot.workflow.model.ExecutionMode;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.ExecutionService;
import com.orchpilot.workflow.service.StartExecutionCommand;
import com.orchpilot.workflow.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Workflow authoring, publishing and execution endpoints.
 */
@RestController
@RequestMapping("/api/workflows")
@Tag(name = "Workflows", description = "Author, publish and execute workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ExecutionService executionService;
    private final AuditService auditService;

    public WorkflowController(WorkflowService workflowService, ExecutionService executionService,
                              AuditService auditService) {
        this.workflowService = workflowService;
        this.executionService = executionService;
        this.auditService = auditService;
    }

    @PostMapping
    @Operation(summary = "Create a workflow",
            description = "Creates a DRAFT workflow. A workflow must be published before it can be executed.")
    public ResponseEntity<WorkflowResponse> create(
            @Valid @RequestBody WorkflowRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        WorkflowResponse response = WorkflowResponse.from(
                workflowService.create(request, ActorResolver.resolve(actorHeader)));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List workflows the caller may view",
            description = """
                    Filtered by the database, not by the caller. An administrator sees everything; anyone
                    else sees the workflows they own plus those shared with a group they belong to that
                    grants WORKFLOW_VIEW.

                    Filtering in the query rather than afterwards is what makes this data isolation rather
                    than presentation: hiding rows after fetching them still leaks through totals, paging and
                    any future endpoint that forgets to apply the filter.""")
    public Page<WorkflowResponse> list(
            @Parameter(description = "Filter by status: DRAFT, PUBLISHED or ARCHIVED")
            @RequestParam(required = false) String status,
            @Parameter(description = "Filter by name substring, case-insensitive")
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        WorkflowStatus parsed = status == null || status.isBlank()
                ? null : WorkflowStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        PageRequest pageable = PageRequest.of(Math.max(0, page), clamp(size),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return workflowService.listAccessible(parsed, name, pageable).map(WorkflowResponse::from);
    }

    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #id)")
    @GetMapping("/{id}")
    @Operation(summary = "Get a workflow")
    public WorkflowResponse get(@PathVariable String id) {
        return WorkflowResponse.from(workflowService.get(id));
    }

    @PreAuthorize("@workflowAuthorizationService.canEdit(authentication, #id)")
    @PutMapping("/{id}")
    @Operation(summary = "Replace a workflow definition",
            description = "Editing a published workflow returns it to DRAFT. The already published version stays "
                    + "executable and running executions are unaffected.")
    public WorkflowResponse update(
            @PathVariable String id,
            @Valid @RequestBody WorkflowRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return WorkflowResponse.from(workflowService.update(id, request, ActorResolver.resolve(actorHeader)));
    }

    @PreAuthorize("@workflowAuthorizationService.canDelete(authentication, #id)")
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a workflow and every version of it")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        workflowService.delete(id, ActorResolver.resolve(actorHeader));
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@workflowAuthorizationService.canPublish(authentication, #id)")
    @PostMapping("/{id}/publish")
    @Operation(summary = "Validate and publish a workflow",
            description = "Snapshots the definition into an immutable version and reconciles its schedule and event "
                    + "triggers. Returns 422 with the full list of problems when the definition is not valid.")
    public PublishResponse publish(
            @PathVariable String id,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        var version = workflowService.publish(id, ActorResolver.resolve(actorHeader));
        return new PublishResponse(version.getWorkflowId(), version.getVersion(), version.getPublishedAt() == null
                ? null : version.getPublishedAt().toString(), workflowService.validate(id).warnings());
    }

    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #id)")
    @PostMapping("/{id}/validate")
    @Operation(summary = "Validate a workflow without publishing it")
    public ValidationResponse validate(@PathVariable String id) {
        return workflowService.validate(id);
    }

    @PreAuthorize("@workflowAuthorizationService.canEdit(authentication, #id)")
    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive a workflow",
            description = "Existing executions continue; no new ones start and its triggers are removed.")
    public WorkflowResponse archive(
            @PathVariable String id,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return WorkflowResponse.from(workflowService.archive(id, ActorResolver.resolve(actorHeader)));
    }

    @PreAuthorize("@workflowAuthorizationService.canExecute(authentication, #id)")
    @PostMapping("/{id}/execute")
    @Operation(summary = "Execute a workflow",
            description = "Runs the published version. With async=false the call returns the finished execution; "
                    + "with async=true it returns immediately with the execution id and status RUNNING. Both use "
                    + "the same engine.")
    public ResponseEntity<ExecutionResponse> execute(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean async,
            @Parameter(description = "Run a specific version instead of the published one")
            @RequestParam(required = false) Integer version,
            @RequestBody(required = false) ExecuteWorkflowRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        ExecuteWorkflowRequest effective = request == null
                ? new ExecuteWorkflowRequest(null, null, null, null, null) : request;
        String actor = ActorResolver.resolve(actorHeader);
        StartExecutionCommand command = new StartExecutionCommand(id, version, effective.safeInput(),
                effective.formData(), effective.correlationId(), effective.idempotencyKey(),
                resolveMode(effective.mode(), async), actor, null, async);
        var execution = executionService.start(command);
        if (async) {
            return ResponseEntity.accepted().body(ExecutionResponse.accepted(execution));
        }
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @PreAuthorize("@workflowAuthorizationService.canViewExecution(authentication.principal.userId, #id)")
    @GetMapping("/{id}/executions")
    @Operation(summary = "List executions of a workflow")
    public Page<ExecutionResponse> executions(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return executionService.list(id, null, PageRequest.of(Math.max(0, page), clamp(size)))
                .map(ExecutionResponse::accepted);
    }

    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #id)")
    @GetMapping("/{id}/audit")
    @Operation(summary = "The change history of a workflow",
            description = "Who created it and who has changed it since — every WORKFLOW_CREATED, "
                    + "WORKFLOW_UPDATED, WORKFLOW_PUBLISHED, WORKFLOW_ARCHIVED and WORKFLOW_DELETED event, each "
                    + "with the actor and the time, most recent first. Takes the same view permission as the "
                    + "workflow itself.")
    public Page<AuditView> audit(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditService.history("WORKFLOW", id, PageRequest.of(Math.max(0, page), clamp(size)))
                .map(AuditView::of);
    }

    @PreAuthorize("@workflowAuthorizationService.canCancelExecution(authentication.principal.userId, #id)")
    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause every in-flight execution of this workflow",
            description = "Bulk control. Pausing, resuming and cancelling apply to executions, not to definitions, "
                    + "so these endpoints act on every non-terminal execution of the workflow. To control one "
                    + "execution, use /api/executions/{executionId}/pause.")
    public BulkControlResponse pauseAll(
            @PathVariable String id,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return applyToActiveExecutions(id, ActorResolver.resolve(actorHeader), "PAUSE");
    }

    @PreAuthorize("@workflowAuthorizationService.canCancelExecution(authentication.principal.userId, #id)")
    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume every paused execution of this workflow",
            description = "Executions waiting for a form are not resumed by this: submit their form instead.")
    public BulkControlResponse resumeAll(
            @PathVariable String id,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return applyToActiveExecutions(id, ActorResolver.resolve(actorHeader), "RESUME");
    }

    @PreAuthorize("@workflowAuthorizationService.canCancelExecution(authentication.principal.userId, #id)")
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel every in-flight execution of this workflow")
    public BulkControlResponse cancelAll(
            @PathVariable String id,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return applyToActiveExecutions(id, ActorResolver.resolve(actorHeader), "CANCEL");
    }

    /**
     * Applies a control action to every non-terminal execution of a workflow, reporting per-execution outcomes
     * rather than failing the whole call when one execution is in an incompatible state.
     */
    private BulkControlResponse applyToActiveExecutions(String workflowId, String actor, String action) {
        workflowService.get(workflowId);
        List<String> affected = new java.util.ArrayList<>();
        List<String> skipped = new java.util.ArrayList<>();
        var page = executionService.list(workflowId, null, PageRequest.of(0, 200));
        for (var execution : page.getContent()) {
            if (execution.getStatus().isTerminal()) {
                continue;
            }
            try {
                switch (action) {
                    case "PAUSE" -> executionService.pause(execution.getId(), actor);
                    case "RESUME" -> executionService.resume(execution.getId(), true, actor);
                    default -> executionService.cancel(execution.getId(), actor);
                }
                affected.add(execution.getId());
            } catch (RuntimeException ex) {
                skipped.add(execution.getId() + ": " + ex.getMessage());
            }
        }
        return new BulkControlResponse(workflowId, action, affected, skipped);
    }

    private static ExecutionMode resolveMode(String requested, boolean async) {
        if (requested != null && !requested.isBlank()) {
            try {
                return ExecutionMode.valueOf(requested.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown execution mode '" + requested + "'. Valid values: "
                        + java.util.Arrays.toString(ExecutionMode.values()));
            }
        }
        return async ? ExecutionMode.ASYNCHRONOUS : ExecutionMode.SYNCHRONOUS;
    }

    private static int clamp(int size) {
        return Math.min(Math.max(1, size), 200);
    }

    /**
     * Result of publishing.
     *
     * @param workflowId  workflow id
     * @param version     version that was published
     * @param publishedAt when it was published
     * @param warnings    non-blocking problems worth knowing about
     */
    public record PublishResponse(String workflowId, int version, String publishedAt, List<String> warnings) {
    }

    /**
     * Result of a bulk execution control action.
     *
     * @param workflowId workflow the action applied to
     * @param action     {@code PAUSE}, {@code RESUME} or {@code CANCEL}
     * @param affected   executions the action succeeded on
     * @param skipped    executions it could not be applied to, with the reason
     */
    public record BulkControlResponse(String workflowId, String action, List<String> affected,
                                      List<String> skipped) {
    }

    /**
     * One entry in a workflow's change history.
     *
     * @param at      when it happened
     * @param actor   the user who did it
     * @param action  {@code WORKFLOW_CREATED}, {@code WORKFLOW_UPDATED}, {@code WORKFLOW_PUBLISHED}, …
     * @param outcome {@code OK}, {@code DENIED} or {@code FAILED}
     * @param details structured context, such as the version a publish produced
     */
    public record AuditView(java.time.Instant at, String actor, String action, String outcome,
                            java.util.Map<String, Object> details) {

        static AuditView of(com.orchpilot.workflow.model.AuditRecord record) {
            return new AuditView(record.getAt(), record.getActor(), record.getAction(),
                    record.getOutcome(),
                    record.getDetails() == null ? java.util.Map.of() : record.getDetails());
        }
    }
}
