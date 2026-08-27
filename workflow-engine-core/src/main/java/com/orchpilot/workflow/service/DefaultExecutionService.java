package com.orchpilot.workflow.service;

import com.orchpilot.workflow.config.AsyncConfig;
import com.orchpilot.workflow.config.EngineInstance;
import com.orchpilot.workflow.dto.FormSubmissionRequest;
import com.orchpilot.workflow.event.ExecutionLifecycleEvent;
import com.orchpilot.workflow.exception.InvalidWorkflowStateException;
import com.orchpilot.workflow.execution.ResumeSignal;
import com.orchpilot.workflow.execution.ExecutionStateStore;
import com.orchpilot.workflow.execution.WorkflowExecutionEngine;
import com.orchpilot.workflow.model.ExecutionLogEntry;
import com.orchpilot.workflow.model.ExecutionMode;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.PendingSignal;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.repository.ExecutionLogRepository;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import com.orchpilot.workflow.variable.VariableScope;
import com.orchpilot.workflow.variable.VariableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Execution orchestration.
 *
 * <p>Notable decisions:
 *
 * <ul>
 *   <li><b>Version pinning happens here, once.</b> The engine is handed a definition, never a workflow id, so it
 *       cannot accidentally resolve a different version on a resume.</li>
 *   <li><b>Execution-level idempotency.</b> A caller that retries a start request with the same key gets the
 *       original execution back. The unique index does the arbitration, so two simultaneous retries cannot both
 *       create one.</li>
 *   <li><b>Asynchronous runs re-read the execution.</b> The pool thread loads its own copy instead of sharing the
 *       object the request thread returned, so the caller's response can never be mutated underneath it.</li>
 *   <li><b>Form data can be supplied up front.</b> A fully specified request runs start to finish without
 *       parking, which is what makes a form-bearing workflow usable as a synchronous API.</li>
 * </ul>
 */
@Service
public class DefaultExecutionService implements ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultExecutionService.class);

    private static final int MAX_LOG_ENTRIES = 1_000;

    private final WorkflowService workflowService;
    private final WorkflowExecutionEngine engine;
    private final WorkflowExecutionRepository executionRepository;
    private final ExecutionLogRepository logRepository;
    private final ExecutionStateStore stateStore;
    private final ThreadPoolTaskExecutor executor;
    private final EngineInstance engineInstance;
    private final AuditService auditService;

    /** Resolves a parked form node to its published form so a raw submission is validated like any other. */
    private final com.orchpilot.workflow.forms.FormNodeBinding formNodeBinding;

    /** Announces terminal transitions that happen outside the engine loop, which the loop cannot announce. */
    private final com.orchpilot.workflow.event.WorkflowEventPublisher eventPublisher;

    public DefaultExecutionService(WorkflowService workflowService, WorkflowExecutionEngine engine,
                                  WorkflowExecutionRepository executionRepository,
                                  ExecutionLogRepository logRepository, ExecutionStateStore stateStore,
                                  @Qualifier(AsyncConfig.WORKFLOW_EXECUTOR) ThreadPoolTaskExecutor executor,
                                  EngineInstance engineInstance, AuditService auditService,
                                  com.orchpilot.workflow.forms.FormNodeBinding formNodeBinding,
                                  com.orchpilot.workflow.event.WorkflowEventPublisher eventPublisher) {
        this.workflowService = workflowService;
        this.engine = engine;
        this.executionRepository = executionRepository;
        this.logRepository = logRepository;
        this.stateStore = stateStore;
        this.executor = executor;
        this.engineInstance = engineInstance;
        this.auditService = auditService;
        this.formNodeBinding = formNodeBinding;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public WorkflowExecution start(StartExecutionCommand command) {
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            Optional<WorkflowExecution> existing =
                    executionRepository.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                log.info("Returning existing execution {} for idempotency key {}", existing.get().getId(),
                        command.idempotencyKey());
                return existing.get();
            }
        }

        WorkflowVersion definition = command.workflowVersion() == null
                ? workflowService.requirePublishedVersion(command.workflowId())
                : workflowService.requireVersion(command.workflowId(), command.workflowVersion());

        WorkflowExecution execution = newExecution(command, definition);
        try {
            execution = executionRepository.save(execution);
        } catch (DuplicateKeyException ex) {
            // Two callers raced on the same idempotency key. The other one won; return its execution.
            Optional<WorkflowExecution> existing =
                    executionRepository.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
            throw ex;
        }

        ResumeSignal signal = initialSignal(definition, command.formData());
        log.info("Starting execution {} of workflow {} v{} ({}{})", execution.getId(), definition.getWorkflowId(),
                definition.getVersion(), command.mode(), command.async() ? ", async" : "");

        if (command.async()) {
            String executionId = execution.getId();
            int version = definition.getVersion();
            String workflowId = definition.getWorkflowId();
            executor.execute(() -> runAsync(executionId, workflowId, version, signal));
            return execution;
        }
        return engine.execute(execution, definition, signal);
    }

    @Override
    public WorkflowExecution get(String executionId) {
        return stateStore.require(executionId);
    }

    @Override
    public Page<WorkflowExecution> list(String workflowId, ExecutionStatus status, Pageable pageable) {
        // The most recently changed execution first, on every path. Without a sort the unfiltered listing came
        // back in whatever order Mongo returned — usually insertion order — so a run that had just changed
        // state sat wherever it happened to be rather than at the top, which is the one place an operator
        // watching executions looks. updatedAt, not startedAt: a resumed or long-running execution that just
        // moved is more current than a newer one that has not, and updatedAt is written on every persist.
        Pageable effective = withLatestFirst(pageable == null ? PageRequest.of(0, 20) : pageable);
        boolean byWorkflow = workflowId != null && !workflowId.isBlank();

        // Both filters, when both are set: an operator looking at one workflow's runs who then picks a status
        // means "that workflow, in that status", not "that workflow, all statuses". Previously the workflow
        // filter won and the status was dropped.
        if (byWorkflow && status != null) {
            return executionRepository.findByWorkflowIdAndStatus(workflowId, status, effective);
        }
        if (byWorkflow) {
            return executionRepository.findByWorkflowId(workflowId, effective);
        }
        if (status != null) {
            return executionRepository.findByStatus(status, effective);
        }
        return executionRepository.findAll(effective);
    }

    /** Applies an {@code updatedAt} descending sort unless the caller already asked for a specific order. */
    private static Pageable withLatestFirst(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @Override
    public List<ExecutionLogEntry> logs(String executionId, int limit) {
        int effective = limit <= 0 ? 200 : Math.min(limit, MAX_LOG_ENTRIES);
        return logRepository.findByExecutionIdOrderBySequenceAsc(executionId, PageRequest.of(0, effective));
    }

    @Override
    public WorkflowExecution submitSignal(String executionId, FormSubmissionRequest request, String actor) {
        WorkflowExecution execution = stateStore.require(executionId);
        if (execution.getStatus() != ExecutionStatus.WAITING) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is " + execution.getStatus()
                    + " and is not waiting for input");
        }
        PendingSignal pending = execution.getPendingSignal();
        if (pending == null || pending.getNodeId() == null) {
            throw new InvalidWorkflowStateException("Execution '" + executionId
                    + "' is waiting but records no pending signal; it cannot be resumed with a submission");
        }
        if (request.nodeId() != null && !request.nodeId().isBlank()
                && !request.nodeId().equals(pending.getNodeId())) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is waiting at node '"
                    + pending.getNodeId() + "', not '" + request.nodeId() + "'");
        }
        if (request.formId() != null && !request.formId().isBlank()
                && pending.getFormId() != null && !request.formId().equals(pending.getFormId())) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is waiting for form '"
                    + pending.getFormId() + "', not '" + request.formId() + "'");
        }
        if (pending.getExpiresAt() != null && pending.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidWorkflowStateException("The pending signal on execution '" + executionId
                    + "' expired at " + pending.getExpiresAt());
        }
        if (engine.isRunningLocally(executionId)) {
            throw new InvalidWorkflowStateException("Execution '" + executionId
                    + "' is being advanced right now; retry in a moment");
        }

        WorkflowVersion definition = workflowService.requireVersion(execution.getWorkflowId(),
                execution.getWorkflowVersion());

        /*
         * Validated here as well as in the task API, because this endpoint is a genuine second entrance. An
         * integration posting straight to it would otherwise bypass every rule the form declares — required
         * fields, patterns, option membership — and write values the console could never have produced.
         */
        formNodeBinding.validateOrThrow(definition.findNode(pending.getNodeId()), request.safeData());

        ResumeSignal signal = ResumeSignal.of(pending.getNodeId(), request.safeData());
        auditService.record(actor, "EXECUTION_SIGNAL_SUBMITTED", "EXECUTION", executionId, "OK",
                Map.of("nodeId", pending.getNodeId(), "fields", request.safeData().keySet()));

        if (Boolean.TRUE.equals(request.async())) {
            String workflowId = execution.getWorkflowId();
            int version = execution.getWorkflowVersion();
            executor.execute(() -> runAsync(executionId, workflowId, version, signal));
            return execution;
        }
        return engine.execute(execution, definition, signal);
    }

    @Override
    public WorkflowExecution resume(String executionId, boolean async, String actor) {
        WorkflowExecution execution = stateStore.require(executionId);
        if (execution.getStatus().isTerminal()) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is "
                    + execution.getStatus() + " and cannot be resumed");
        }
        if (execution.getStatus() == ExecutionStatus.WAITING) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is waiting for input. "
                    + "Submit it with POST /api/executions/" + executionId + "/form instead.");
        }
        if (engine.isRunningLocally(executionId)) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is already running here");
        }
        // Clearing PAUSED before re-entering the loop matters: the engine checks the persisted status at each
        // node boundary and would otherwise stop again immediately.
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setOwnerInstance(engineInstance.id());
        execution = stateStore.save(execution);

        WorkflowVersion definition = workflowService.requireVersion(execution.getWorkflowId(),
                execution.getWorkflowVersion());
        auditService.record(actor, "EXECUTION_RESUMED", "EXECUTION", executionId, "OK", null);

        if (async) {
            String workflowId = execution.getWorkflowId();
            int version = execution.getWorkflowVersion();
            executor.execute(() -> runAsync(executionId, workflowId, version, null));
            return execution;
        }
        return engine.execute(execution, definition, null);
    }

    @Override
    public WorkflowExecution pause(String executionId, String actor) {
        WorkflowExecution execution = stateStore.require(executionId);
        if (execution.getStatus().isTerminal()) {
            throw new InvalidWorkflowStateException("Execution '" + executionId + "' is "
                    + execution.getStatus() + " and cannot be paused");
        }
        if (execution.getStatus() == ExecutionStatus.PAUSED) {
            return execution;
        }
        execution.setStatus(ExecutionStatus.PAUSED);
        WorkflowExecution saved = stateStore.save(execution);
        auditService.record(actor, "EXECUTION_PAUSED", "EXECUTION", executionId, "OK", null);
        log.info("Execution {} marked PAUSED; a running loop will stop at its next node boundary", executionId);
        return saved;
    }

    @Override
    public WorkflowExecution cancel(String executionId, String actor) {
        WorkflowExecution execution = stateStore.require(executionId);
        if (execution.getStatus().isTerminal()) {
            return execution;
        }
        // Signal the local loop first so a long-running node stops promptly, then persist the terminal status so
        // an instance elsewhere in the cluster sees it at its next boundary.
        boolean signalled = engine.requestCancellation(executionId);
        execution.setStatus(ExecutionStatus.CANCELLED);
        execution.setCompletedAt(Instant.now());
        execution.setPendingSignal(null);
        WorkflowExecution saved = stateStore.save(execution);
        auditService.record(actor, "EXECUTION_CANCELLED", "EXECUTION", executionId, "OK",
                Map.of("signalledLocally", signalled));

        /*
         * Announce it here as well as in the engine.
         *
         * The engine publishes CANCELLED from inside its loop, which covers a running execution and nothing
         * else. An execution parked on a human task has no loop, so cancelling it used to change the status and
         * tell nobody: the task it raised stayed in somebody's inbox for a workflow that no longer existed,
         * and any listener watching for terminal transitions simply never saw this one.
         */
        eventPublisher.publishExecutionEvent(ExecutionLifecycleEvent.of(executionId,
                saved.getWorkflowId(), saved.getWorkflowVersion(), ExecutionStatus.CANCELLED,
                saved.getCurrentNodeId()));

        log.info("Execution {} cancelled by {} (running here: {})", executionId, actor, signalled);
        return saved;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs an execution on a pool thread, loading its own copy of the document.
     */
    private void runAsync(String executionId, String workflowId, int version, ResumeSignal signal) {
        try {
            WorkflowExecution fresh = stateStore.require(executionId);
            if (fresh.getStatus().isTerminal()) {
                log.info("Skipping asynchronous run of {}: already {}", executionId, fresh.getStatus());
                return;
            }
            WorkflowVersion definition = workflowService.requireVersion(workflowId, version);
            engine.execute(fresh, definition, signal);
        } catch (RuntimeException ex) {
            // Nothing is waiting on this thread, so the failure has to be recorded on the execution or it is
            // lost entirely.
            log.error("Asynchronous execution {} failed outside the engine loop: {}", executionId,
                    ex.getMessage(), ex);
            markFailedOutsideEngine(executionId, ex);
        }
    }

    private void markFailedOutsideEngine(String executionId, RuntimeException cause) {
        try {
            WorkflowExecution execution = stateStore.require(executionId);
            if (execution.getStatus().isTerminal()) {
                return;
            }
            execution.setStatus(ExecutionStatus.FAILED);
            execution.setCompletedAt(Instant.now());
            execution.setError(new com.orchpilot.workflow.model.ExecutionError("ENGINE_ERROR",
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                    execution.getCurrentNodeId()));
            WorkflowExecution saved = stateStore.save(execution);
            // Same reasoning as cancel: this failure happens outside the engine loop, so the loop's own
            // publication never runs and a listener would never learn the execution had ended.
            eventPublisher.publishExecutionEvent(ExecutionLifecycleEvent.of(executionId,
                    saved.getWorkflowId(), saved.getWorkflowVersion(), ExecutionStatus.FAILED,
                    saved.getCurrentNodeId()));
        } catch (RuntimeException ex) {
            log.error("Could not record the failure of execution {}: {}", executionId, ex.getMessage());
        }
    }

    private WorkflowExecution newExecution(StartExecutionCommand command, WorkflowVersion definition) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setWorkflowId(definition.getWorkflowId());
        execution.setWorkflowVersion(definition.getVersion());
        execution.setWorkflowName(definition.getName());
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setMode(command.mode() == null ? ExecutionMode.SYNCHRONOUS : command.mode());
        execution.setCreatedAt(Instant.now());
        execution.setUpdatedAt(Instant.now());
        execution.setHeartbeatAt(Instant.now());
        execution.setOwnerInstance(engineInstance.id());
        /*
         * Attribute the run to the authenticated caller.
         *
         * Read from the security context rather than taken from the request, so a client cannot claim to be
         * someone else by setting a field. Absent for a scheduled or event-driven start, which has no
         * authenticated principal and is attributed to whatever the trigger supplied.
         */
        com.orchpilot.workflow.auth.security.CurrentUser.principal().ifPresentOrElse(principal -> {
            execution.setTriggeredBy(principal.getUsername());
            execution.setTriggeredByUserId(principal.getUserId());
            execution.setTriggeredByRoles(principal.getRoles().stream().map(Enum::name).toList());
        }, () -> execution.setTriggeredBy(command.triggeredBy()));
        execution.setTriggerId(command.triggerId());
        execution.setCorrelationId(command.correlationId());
        execution.setIdempotencyKey(command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? null : command.idempotencyKey());

        // Seed the variable scopes now so the persisted document is complete even before the engine runs, which
        // matters if the process dies between the insert and the first node.
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.INPUT, command.safeInput());
        store.seed(VariableScope.WORKFLOW, definition.getVariables());
        execution.setVariables(store.snapshot());
        return execution;
    }

    /**
     * Turns caller-supplied form data into a signal for the first form node in the graph.
     *
     * <p>Only the first form node is targeted, because that is the one a start request can know about. Later form
     * nodes park normally and are submitted through the form endpoint.
     */
    private ResumeSignal initialSignal(WorkflowVersion definition, Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return null;
        }
        for (WorkflowNode node : definition.getNodes()) {
            if (NodeTypes.FORM.equals(node.getType())) {
                return ResumeSignal.of(node.getId(), formData);
            }
        }
        log.debug("Form data was supplied but workflow {} v{} has no form node", definition.getWorkflowId(),
                definition.getVersion());
        return null;
    }
}
