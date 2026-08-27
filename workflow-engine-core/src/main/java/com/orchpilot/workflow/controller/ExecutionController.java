package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.dto.ExecutionLogResponse;
import com.orchpilot.workflow.dto.ExecutionResponse;
import com.orchpilot.workflow.dto.FormSubmissionRequest;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.service.ExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Execution inspection and control endpoints.
 */
@RestController
@RequestMapping("/api/executions")
@Tag(name = "Executions", description = "Inspect, resume and control workflow executions")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping
    @Operation(summary = "List executions")
    public Page<ExecutionResponse> list(
            @RequestParam(required = false) String workflowId,
            @Parameter(description = "Filter by status: PENDING, RUNNING, WAITING, PAUSED, COMPLETED, FAILED "
                    + "or CANCELLED")
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ExecutionStatus parsed = status == null || status.isBlank()
                ? null : ExecutionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        return executionService.list(workflowId, parsed,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200)))
                .map(ExecutionResponse::accepted);
    }

    @PreAuthorize("@workflowAuthorizationService.canViewExecutionById(authentication, #executionId)")
    @GetMapping("/{executionId}")
    @Operation(summary = "Get an execution",
            description = "Includes the variable snapshot and per-node history. Known secret values are redacted "
                    + "before anything is persisted, so neither appears here.")
    public ExecutionResponse get(@PathVariable String executionId) {
        return ExecutionResponse.from(executionService.get(executionId));
    }

    @PreAuthorize("@workflowAuthorizationService.canViewExecutionById(authentication, #executionId)")
    @GetMapping("/{executionId}/logs")
    @Operation(summary = "Get an execution's structured log")
    public List<ExecutionLogResponse> logs(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "200") int limit) {
        return executionService.logs(executionId, limit).stream().map(ExecutionLogResponse::from).toList();
    }

    @PreAuthorize("@workflowAuthorizationService.canSubmitFormById(authentication, #executionId)")
    @PostMapping("/{executionId}/form")
    @Operation(summary = "Submit a form and resume the execution",
            description = "Satisfies the node the execution is parked at. The submitted fields become that node's "
                    + "outputs, so its output mapping promotes them into workflow variables.")
    public ExecutionResponse submitForm(
            @PathVariable String executionId,
            @RequestBody FormSubmissionRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        var execution = executionService.submitSignal(executionId, request, ActorResolver.resolve(actorHeader));
        return ExecutionResponse.from(execution);
    }

    @PreAuthorize("@workflowAuthorizationService.canRetryExecutionById(authentication, #executionId)")
    @PostMapping("/{executionId}/resume")
    @Operation(summary = "Resume a paused execution",
            description = "For an execution waiting on a form, submit the form instead.")
    public ExecutionResponse resume(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "true") boolean async,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return ExecutionResponse.from(
                executionService.resume(executionId, async, ActorResolver.resolve(actorHeader)));
    }

    @PreAuthorize("@workflowAuthorizationService.canCancelExecutionById(authentication, #executionId)")
    @PostMapping("/{executionId}/pause")
    @Operation(summary = "Pause an execution",
            description = "A running execution stops at its next node boundary, wherever in the cluster it is "
                    + "running. The node in flight is allowed to finish.")
    public ExecutionResponse pause(
            @PathVariable String executionId,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return ExecutionResponse.from(executionService.pause(executionId, ActorResolver.resolve(actorHeader)));
    }

    @PreAuthorize("@workflowAuthorizationService.canCancelExecutionById(authentication, #executionId)")
    @PostMapping("/{executionId}/cancel")
    @Operation(summary = "Cancel an execution")
    public ExecutionResponse cancel(
            @PathVariable String executionId,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        return ExecutionResponse.from(executionService.cancel(executionId, ActorResolver.resolve(actorHeader)));
    }

    @PreAuthorize("@workflowAuthorizationService.canViewExecutionById(authentication, #executionId)")
    @GetMapping("/{executionId}/pending")
    @Operation(summary = "Get what a waiting execution is waiting for",
            description = "Returns 204 when the execution is not waiting. Intended for a task inbox that renders "
                    + "outstanding forms.")
    public ResponseEntity<ExecutionResponse.PendingSignalView> pending(@PathVariable String executionId) {
        var execution = executionService.get(executionId);
        var view = ExecutionResponse.from(execution).pendingSignal();
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }
}
