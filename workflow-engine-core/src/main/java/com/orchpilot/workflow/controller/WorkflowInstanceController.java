package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.model.AuditRecord;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.WorkflowInstanceLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runtime lifecycle control for a single workflow <em>instance</em>: pause, resume, terminate.
 *
 * <h2>Instance, not template</h2>
 *
 * Every endpoint here acts on one running instance ({@code WorkflowExecution}) and never on the workflow
 * design. Pausing instance #123 of "Customer Onboarding" leaves every other instance of that workflow running,
 * and changes nothing about the workflow itself. The controls are gated on dedicated instance permissions,
 * separate from the permissions that edit or execute a workflow.
 */
@RestController
@RequestMapping("/api/workflow-instances")
@Tag(name = "Workflow instances", description = "Pause, resume and terminate running workflow instances")
public class WorkflowInstanceController {

    private final WorkflowInstanceLifecycleService lifecycle;
    private final AuditService audit;

    public WorkflowInstanceController(WorkflowInstanceLifecycleService lifecycle, AuditService audit) {
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    /** Optional reason carried on a pause or terminate, recorded in the lifecycle history. */
    public record ReasonRequest(String reason) {
        String safeReason() {
            return reason == null || reason.isBlank() ? null : reason.trim();
        }
    }

    /** The runtime state of one instance. */
    public record InstanceStatus(String instanceId, String workflowTemplateId, int workflowVersion,
                                 String status, String currentNodeId, String statusBeforePause,
                                 String pauseReason, String terminationReason, String terminatedBy,
                                 Instant terminatedAt, Instant startedAt, Instant completedAt,
                                 Instant updatedAt) {

        static InstanceStatus of(WorkflowExecution instance) {
            return new InstanceStatus(instance.getId(), instance.getWorkflowId(),
                    instance.getWorkflowVersion(), instance.getStatus().name(), instance.getCurrentNodeId(),
                    instance.getStatusBeforePause() == null ? null : instance.getStatusBeforePause().name(),
                    instance.getPauseReason(), instance.getTerminationReason(), instance.getTerminatedBy(),
                    instance.getTerminatedAt(), instance.getStartedAt(), instance.getCompletedAt(),
                    instance.getUpdatedAt());
        }
    }

    /** One entry in an instance's lifecycle history. */
    public record HistoryEntry(Instant at, String actor, String event, String outcome,
                               Map<String, Object> details) {

        static HistoryEntry of(AuditRecord record) {
            return new HistoryEntry(record.getAt(), record.getActor(), record.getAction(),
                    record.getOutcome(), record.getDetails() == null ? Map.of() : record.getDetails());
        }
    }

    @PreAuthorize("hasAuthority('WORKFLOW_INSTANCE_PAUSE')")
    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a running instance",
            description = "Stops the instance progressing and holds its active tasks. Assignees can still save "
                    + "form drafts but cannot submit until the instance is resumed. Idempotent: pausing an "
                    + "already-paused instance is a no-op.")
    public InstanceStatus pause(@PathVariable String id, @RequestBody(required = false) ReasonRequest request) {
        String reason = request == null ? null : request.safeReason();
        return InstanceStatus.of(lifecycle.pause(id, reason, actor()));
    }

    @PreAuthorize("hasAuthority('WORKFLOW_INSTANCE_RESUME')")
    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a paused instance",
            description = "Returns held tasks to their pre-pause status and continues the instance. Only a "
                    + "paused instance can be resumed; a terminated one never can.")
    public InstanceStatus resume(@PathVariable String id) {
        return InstanceStatus.of(lifecycle.resume(id, actor()));
    }

    @PreAuthorize("hasAuthority('WORKFLOW_INSTANCE_TERMINATE')")
    @PostMapping("/{id}/terminate")
    @Operation(summary = "Terminate an instance permanently",
            description = "Ends the instance and its active tasks for good. A terminated instance cannot be "
                    + "resumed, restarted or continued. Idempotent: terminating an already-terminated instance "
                    + "is a no-op.")
    public InstanceStatus terminate(@PathVariable String id,
                                    @RequestBody(required = false) ReasonRequest request) {
        String reason = request == null ? null : request.safeReason();
        return InstanceStatus.of(lifecycle.terminate(id, reason, actor()));
    }

    @PreAuthorize("hasAuthority('EXECUTION_VIEW')")
    @GetMapping("/{id}/status")
    @Operation(summary = "The current runtime state of an instance")
    public InstanceStatus status(@PathVariable String id) {
        return InstanceStatus.of(lifecycle.status(id));
    }

    @PreAuthorize("hasAuthority('EXECUTION_VIEW')")
    @GetMapping("/{id}/history")
    @Operation(summary = "The lifecycle history of an instance",
            description = "Every INSTANCE_STARTED, INSTANCE_PAUSED, INSTANCE_RESUMED, INSTANCE_TERMINATED and "
                    + "INSTANCE_COMPLETED event, newest first, each with the actor, the time and the reason. "
                    + "Never carries form values.")
    public List<HistoryEntry> history(@PathVariable String id,
                                      @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(1, limit), 200);
        return audit.history("WORKFLOW_INSTANCE", id, PageRequest.of(0, size)).stream()
                .map(HistoryEntry::of)
                .toList();
    }

    private String actor() {
        AuthPrincipal principal = CurrentUser.principal().orElseThrow(() ->
                new AccessDeniedException("This endpoint requires an authenticated user"));
        return principal.getUsername();
    }
}
