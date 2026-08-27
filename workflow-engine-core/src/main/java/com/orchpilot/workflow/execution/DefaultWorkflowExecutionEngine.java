package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.config.EngineInstance;
import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.event.ExecutionLifecycleEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.exception.NodeExecutorNotFoundException;
import com.orchpilot.workflow.expression.ExpressionEvaluator;
import com.orchpilot.workflow.model.ErrorPolicy;
import com.orchpilot.workflow.model.ExecutionError;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.NodeExecutionRecord;
import com.orchpilot.workflow.model.PendingSignal;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.node.WorkflowNodeExecutor;
import com.orchpilot.workflow.node.WorkflowNodeRegistry;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.variable.VariableMapper;
import com.orchpilot.workflow.variable.VariableResolver;
import com.orchpilot.workflow.variable.VariableScope;
import com.orchpilot.workflow.variable.VariableStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The execution loop.
 *
 * <p>Iterative, never recursive: a workflow with a thousand nodes, or a deliberate cycle, must not
 * exhaust the stack. The loop resolves an executor per node from the registry and knows nothing about
 * what any node does; the only thing it asks an executor besides "execute" is whether it is terminal.
 *
 * <p>State is persisted at every node boundary. That is a write per node, and it buys three properties
 * that matter more than the writes cost: a crash resumes at the last completed node instead of the
 * start, an operator can watch a running execution progress, and a parked form holds no thread.
 *
 * <p>Concurrency:
 * <ul>
 *   <li>A second concurrent call for the same execution is refused, so a retrying caller cannot double
 *       up on side effects.</li>
 *   <li>Each boundary re-reads the persisted status, so cancel and pause issued on another instance take
 *       effect promptly.</li>
 *   <li>Persistence uses optimistic locking. Losing that race means another writer owns the execution,
 *       and this loop steps aside rather than overwriting.</li>
 * </ul>
 */
@Component
public class DefaultWorkflowExecutionEngine implements WorkflowExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowExecutionEngine.class);

    private static final int MAX_HISTORY_RECORDS = 500;

    private final WorkflowNodeRegistry registry;
    private final NodeRetryTemplate retryTemplate;
    private final ExecutionStateStore stateStore;
    private final ExecutionLogWriter logWriter;
    private final VariableResolver variableResolver;
    private final VariableMapper variableMapper;
    private final ExpressionEvaluator expressionEvaluator;
    private final WorkflowEventPublisher eventPublisher;
    private final ExecutionHeartbeatService heartbeatService;
    private final EngineInstance engineInstance;
    private final WorkflowEngineProperties properties;

    /** Executions this instance is advancing, and the context each one can be cancelled through. */
    private final ConcurrentHashMap<String, WorkflowExecutionContext> local = new ConcurrentHashMap<>();

    public DefaultWorkflowExecutionEngine(WorkflowNodeRegistry registry, NodeRetryTemplate retryTemplate,
                                          ExecutionStateStore stateStore, ExecutionLogWriter logWriter,
                                          VariableResolver variableResolver, VariableMapper variableMapper,
                                          ExpressionEvaluator expressionEvaluator,
                                          WorkflowEventPublisher eventPublisher,
                                          ExecutionHeartbeatService heartbeatService,
                                          EngineInstance engineInstance,
                                          WorkflowEngineProperties properties) {
        this.registry = registry;
        this.retryTemplate = retryTemplate;
        this.stateStore = stateStore;
        this.logWriter = logWriter;
        this.variableResolver = variableResolver;
        this.variableMapper = variableMapper;
        this.expressionEvaluator = expressionEvaluator;
        this.eventPublisher = eventPublisher;
        this.heartbeatService = heartbeatService;
        this.engineInstance = engineInstance;
        this.properties = properties;
    }

    @Override
    public WorkflowExecution execute(WorkflowExecution execution, WorkflowVersion definition,
                                     ResumeSignal signal) {
        String executionId = execution.getId();
        VariableStore store = VariableStore.fromSnapshot(execution.getVariables());
        WorkflowExecutionContext context = new WorkflowExecutionContext(execution, definition, store,
                variableResolver, variableMapper, expressionEvaluator, logWriter,
                logWriter.countFor(executionId));

        if (local.putIfAbsent(executionId, context) != null) {
            log.warn("Execution {} is already running on this instance; ignoring the duplicate request",
                    executionId);
            return execution;
        }
        heartbeatService.track(executionId);
        try {
            if (signal != null && signal.nodeId() != null) {
                context.offerSignal(signal.nodeId(), signal.data());
            }
            return runLoop(execution, definition, context, store);
        } finally {
            local.remove(executionId);
            heartbeatService.release(executionId);
        }
    }

    @Override
    public boolean requestCancellation(String executionId) {
        WorkflowExecutionContext context = local.get(executionId);
        if (context == null) {
            return false;
        }
        context.cancel();
        log.info("Cancellation requested for locally running execution {}", executionId);
        return true;
    }

    @Override
    public boolean isRunningLocally(String executionId) {
        return local.containsKey(executionId);
    }

    // ------------------------------------------------------------------ loop

    private WorkflowExecution runLoop(WorkflowExecution execution, WorkflowVersion definition,
                                     WorkflowExecutionContext context, VariableStore store) {
        String startNodeId;
        try {
            startNodeId = resolveEntryNode(execution, definition, context);
        } catch (IllegalStateException ex) {
            return fail(execution, store, context, "WORKFLOW_ENTRY_UNRESOLVED", ex.getMessage(),
                    execution.getCurrentNodeId());
        }

        seedSystemVariables(execution, definition, store);
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setOwnerInstance(engineInstance.id());
        execution.setPendingSignal(null);
        if (execution.getStartedAt() == null) {
            execution.setStartedAt(Instant.now());
        }
        if (!persist(execution, store)) {
            return execution;
        }
        eventPublisher.publishExecutionEvent(ExecutionLifecycleEvent.of(execution.getId(),
                execution.getWorkflowId(), execution.getWorkflowVersion(), ExecutionStatus.RUNNING,
                startNodeId));

        String nodeId = startNodeId;
        int maxSteps = Math.max(1, properties.getExecution().getMaxSteps());

        while (nodeId != null) {
            Optional<WorkflowExecution> interrupted = checkInterruption(execution, store, context);
            if (interrupted.isPresent()) {
                return interrupted.get();
            }
            if (execution.getStepCount() >= maxSteps) {
                return fail(execution, store, context, "STEP_LIMIT_EXCEEDED",
                        "Execution exceeded the configured limit of " + maxSteps + " node executions, "
                                + "which usually means the graph contains an unbounded cycle",
                        nodeId);
            }

            WorkflowNode node = definition.findNode(nodeId);
            if (node == null) {
                return fail(execution, store, context, "NODE_NOT_FOUND",
                        "Connection points at node '" + nodeId + "', which the workflow version does not "
                                + "define", nodeId);
            }
            execution.setCurrentNodeId(nodeId);
            store.seed(VariableScope.SYSTEM, Map.of("nodeId", nodeId, "now", Instant.now().toString()));

            WorkflowNodeExecutor executor;
            try {
                executor = registry.resolve(node);
            } catch (NodeExecutorNotFoundException ex) {
                return fail(execution, store, context, ex.getErrorCode(), ex.getMessage(), nodeId);
            }

            Instant nodeStart = Instant.now();
            // Publish the in-flight node before running it, so a console can show a long node as working rather
            // than showing nothing at all. A version-safe $set, so it cannot invalidate the save below.
            execution.setCurrentNodeType(node.getType());
            execution.setCurrentNodeStartedAt(nodeStart);
            stateStore.markNodeInFlight(execution.getId(), nodeId, node.getType(), nodeStart);
            NodeExecutionResult result = retryTemplate.execute(executor, node, context);
            execution.setStepCount(execution.getStepCount() + 1);
            recordNode(execution, node, result, nodeStart, context.currentAttempt());

            switch (result.status()) {
                case SUCCESS -> {
                    try {
                        applyOutputs(node, result, store);
                    } catch (RuntimeException ex) {
                        /*
                         * The node did its work and then produced something the execution cannot carry — an
                         * output key containing a dot is the case seen in practice. Failing here, with the
                         * message the store gave, is the difference between a workflow that says what a
                         * plugin did wrong and one that sits in RUNNING for ever with its side effect already
                         * performed.
                         */
                        return fail(execution, store, context, "NODE_OUTPUT_REJECTED",
                                "The node ran, but its outputs could not be published: " + ex.getMessage(),
                                nodeId);
                    }
                    if (!persist(execution, store)) {
                        return execution;
                    }
                    if (executor.isTerminal()) {
                        return complete(execution, store, context, result, node.getId());
                    }
                    Optional<String> next = nextNode(context, node, result.selectedBranch());
                    if (next.isEmpty()) {
                        context.logInfo(node.getId(), node.getType(),
                                "No outgoing connection; treating this node as the end of the workflow", null);
                        return complete(execution, store, context, result, node.getId());
                    }
                    nodeId = next.get();
                }
                case SKIPPED -> {
                    if (!persist(execution, store)) {
                        return execution;
                    }
                    Optional<String> next = nextNode(context, node, null);
                    if (next.isEmpty()) {
                        return complete(execution, store, context, result, node.getId());
                    }
                    nodeId = next.get();
                }
                case WAITING -> {
                    return park(execution, store, context, node, result);
                }
                case FAILED -> {
                    StepDecision decision = handleFailure(execution, store, context, node, result);
                    if (decision.stop()) {
                        return execution;
                    }
                    nodeId = decision.nextNodeId();
                    if (nodeId == null) {
                        return complete(execution, store, context, result, node.getId());
                    }
                }
                default -> {
                    return fail(execution, store, context, "UNKNOWN_NODE_RESULT",
                            "Node returned an unrecognised status: " + result.status(), nodeId);
                }
            }
        }
        return complete(execution, store, context, NodeExecutionResult.success(),
                execution.getCurrentNodeId());
    }

    // ------------------------------------------------------------- decisions

    /**
     * Where a failure leaves the loop: either stop, or continue at a node.
     *
     * @param nextNodeId node to continue at, {@code null} to complete the workflow
     * @param stop       whether the execution has reached a terminal state
     */
    private record StepDecision(String nextNodeId, boolean stop) {

        static StepDecision stopHere() {
            return new StepDecision(null, true);
        }

        static StepDecision continueAt(String nodeId) {
            return new StepDecision(nodeId, false);
        }
    }

    private StepDecision handleFailure(WorkflowExecution execution, VariableStore store,
                                       WorkflowExecutionContext context, WorkflowNode node,
                                       NodeExecutionResult result) {
        ErrorPolicy policy = node.effectiveErrorPolicy();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errorCode", result.errorCode());
        details.put("errorPolicy", policy.name());
        context.logError(node.getId(), node.getType(),
                "Node failed: " + result.errorMessage(), details);

        switch (policy) {
            case SKIP -> {
                Optional<String> next = nextNode(context, node, null);
                persist(execution, store);
                return next.map(StepDecision::continueAt).orElseGet(() -> new StepDecision(null, false));
            }
            case CONTINUE -> {
                // Publish the failure as node output so a later decision node can branch on it. This is
                // the difference between CONTINUE and SKIP: the workflow learns that the node failed.
                Map<String, Object> failureOutputs = new LinkedHashMap<>();
                failureOutputs.put("failed", Boolean.TRUE);
                failureOutputs.put("errorCode", result.errorCode());
                failureOutputs.put("errorMessage", result.errorMessage());
                store.putNodeOutputs(node.getId(), failureOutputs);
                variableMapper.applyOutputMapping(node.getOutputMapping(), failureOutputs, store);
                Optional<String> next = nextNode(context, node, null);
                persist(execution, store);
                return next.map(StepDecision::continueAt).orElseGet(() -> new StepDecision(null, false));
            }
            case COMPENSATE -> {
                runCompensation(execution, store, context, node);
                fail(execution, store, context, result.errorCode(), result.errorMessage(), node.getId());
                return StepDecision.stopHere();
            }
            case RETRY, FAIL_WORKFLOW -> {
                fail(execution, store, context, result.errorCode(), result.errorMessage(), node.getId());
                return StepDecision.stopHere();
            }
            default -> {
                fail(execution, store, context, result.errorCode(), result.errorMessage(), node.getId());
                return StepDecision.stopHere();
            }
        }
    }

    /**
     * Runs a compensation node once, with no retry and no error policy.
     *
     * <p>Compensation that itself fails must not start a second round of compensation, so the result is
     * recorded and the original failure stands regardless.
     */
    private void runCompensation(WorkflowExecution execution, VariableStore store,
                                 WorkflowExecutionContext context, WorkflowNode failedNode) {
        String compensationId = failedNode.getCompensationNodeId();
        if (compensationId == null || compensationId.isBlank()) {
            context.logWarn(failedNode.getId(), failedNode.getType(),
                    "Error policy is COMPENSATE but no compensationNodeId is set", null);
            return;
        }
        WorkflowNode compensation = context.definition().findNode(compensationId);
        if (compensation == null) {
            context.logWarn(failedNode.getId(), failedNode.getType(),
                    "Compensation node '" + compensationId + "' is not defined", null);
            return;
        }
        try {
            WorkflowNodeExecutor executor = registry.resolve(compensation);
            Instant start = Instant.now();
            NodeExecutionResult result = executor.execute(compensation, context);
            recordNode(execution, compensation, result == null ? NodeExecutionResult.success() : result,
                    start, 1);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("compensationNodeId", compensationId);
            details.put("status", result == null ? "SUCCESS" : result.status().name());
            context.logInfo(failedNode.getId(), failedNode.getType(), "Compensation executed", details);
        } catch (RuntimeException ex) {
            context.logError(compensationId, compensation.getType(),
                    "Compensation node failed: " + ex.getMessage(), null);
        }
    }

    /**
     * Chooses the next node, honouring both branch ports and per-edge guard conditions.
     *
     * <p>A guard that fails to evaluate is treated as not satisfied and logged, for the same reason a
     * decision condition is: an unset variable on an untaken path is normal, and failing the workflow for
     * it would make guards unusable.
     */
    private Optional<String> nextNode(WorkflowExecutionContext context, WorkflowNode node,
                                      String selectedBranch) {
        List<WorkflowConnection> candidates = context.graph().outgoing(node.getId());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (selectedBranch != null && !selectedBranch.isBlank()) {
            for (WorkflowConnection connection : candidates) {
                if (selectedBranch.equals(connection.getSourcePort()) && guardSatisfied(context, connection)) {
                    return Optional.ofNullable(connection.getTarget());
                }
            }
            context.logWarn(node.getId(), node.getType(),
                    "No outgoing connection for branch '" + selectedBranch + "'; using the default edge",
                    Map.of("branch", selectedBranch));
        }
        for (WorkflowConnection connection : candidates) {
            if (connection.isDefaultEdge() && guardSatisfied(context, connection)) {
                return Optional.ofNullable(connection.getTarget());
            }
        }
        return Optional.empty();
    }

    private boolean guardSatisfied(WorkflowExecutionContext context, WorkflowConnection connection) {
        String condition = connection.getCondition();
        if (condition == null || condition.isBlank()) {
            return true;
        }
        try {
            return context.evaluateCondition(condition);
        } catch (RuntimeException ex) {
            log.warn("Edge guard '{}' on {} treated as false: {}", condition, connection, ex.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ transitions

    private WorkflowExecution complete(WorkflowExecution execution, VariableStore store,
                                       WorkflowExecutionContext context, NodeExecutionResult result,
                                       String nodeId) {
        Map<String, Object> output = new LinkedHashMap<>(store.scopeSnapshot(VariableScope.OUTPUT));
        if (output.isEmpty()) {
            output.putAll(result.outputs());
        }
        execution.setOutput(output);
        execution.setStatus(ExecutionStatus.COMPLETED);
        execution.setCompletedAt(Instant.now());
        execution.setCurrentNodeId(nodeId);
        execution.setPendingSignal(null);
        persist(execution, store);

        context.logInfo(nodeId, null, "Execution completed", Map.of("outputKeys", output.keySet()));
        log.info("Execution {} of workflow {} v{} completed in {} ms after {} step(s)", execution.getId(),
                execution.getWorkflowId(), execution.getWorkflowVersion(), elapsedMillis(execution),
                execution.getStepCount());
        eventPublisher.publishExecutionEvent(new ExecutionLifecycleEvent(execution.getId(),
                execution.getWorkflowId(), execution.getWorkflowVersion(), ExecutionStatus.COMPLETED, nodeId,
                output, null, Instant.now()));
        return execution;
    }

    private WorkflowExecution fail(WorkflowExecution execution, VariableStore store,
                                   WorkflowExecutionContext context, String errorCode, String message,
                                   String nodeId) {
        ExecutionError error = new ExecutionError(errorCode, message, nodeId);
        execution.setError(error);
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setCompletedAt(Instant.now());
        execution.setCurrentNodeId(nodeId);
        execution.setPendingSignal(null);
        persist(execution, store);

        if (context != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("errorCode", errorCode);
            details.put("nodeId", nodeId);
            context.logError(nodeId, null, "Execution failed: " + message, details);
        }
        log.warn("Execution {} of workflow {} failed at node {}: {} - {}", execution.getId(),
                execution.getWorkflowId(), nodeId, errorCode, message);
        eventPublisher.publishExecutionEvent(new ExecutionLifecycleEvent(execution.getId(),
                execution.getWorkflowId(), execution.getWorkflowVersion(), ExecutionStatus.FAILED, nodeId,
                Map.of(), errorCode, Instant.now()));
        return execution;
    }

    private WorkflowExecution park(WorkflowExecution execution, VariableStore store,
                                   WorkflowExecutionContext context, WorkflowNode node,
                                   NodeExecutionResult result) {
        PendingSignal pending = new PendingSignal();
        pending.setNodeId(node.getId());
        pending.setType(node.getFormId() != null || "FORM".equals(node.getType()) ? "FORM" : "SIGNAL");
        pending.setFormId(node.getFormId() != null ? node.getFormId()
                : String.valueOf(result.outputs().getOrDefault("formId", node.getId())));
        pending.setReason(result.waitReason());
        pending.setRequestedAt(Instant.now());
        pending.setPayload(new LinkedHashMap<>(result.outputs()));
        Object expiresIn = result.outputs().get("expiresInSeconds");
        if (expiresIn instanceof Number) {
            pending.setExpiresAt(Instant.now().plus(Duration.ofSeconds(((Number) expiresIn).longValue())));
        }

        execution.setPendingSignal(pending);
        execution.setStatus(ExecutionStatus.WAITING);
        execution.setCurrentNodeId(node.getId());
        persist(execution, store);

        log.info("Execution {} parked at node {} ({})", execution.getId(), node.getId(),
                result.waitReason());
        eventPublisher.publishExecutionEvent(ExecutionLifecycleEvent.of(execution.getId(),
                execution.getWorkflowId(), execution.getWorkflowVersion(), ExecutionStatus.WAITING,
                node.getId()));
        return execution;
    }

    /**
     * @return the execution when the loop must stop because of cancellation or a pause, otherwise empty
     */
    private Optional<WorkflowExecution> checkInterruption(WorkflowExecution execution, VariableStore store,
                                                          WorkflowExecutionContext context) {
        if (context.isCancelled()) {
            return Optional.of(cancel(execution, store, context, "Cancellation requested"));
        }
        Optional<ExecutionStatus> persisted = stateStore.currentStatus(execution.getId());
        if (persisted.isEmpty()) {
            log.warn("Execution {} disappeared while running; stopping", execution.getId());
            return Optional.of(execution);
        }
        ExecutionStatus status = persisted.get();
        if (status == ExecutionStatus.CANCELLED) {
            return Optional.of(cancel(execution, store, context, "Cancelled by another instance"));
        }
        if (status == ExecutionStatus.TERMINATED) {
            /*
             * An administrator terminated the instance while this loop was between nodes. Stop permanently and
             * do NOT persist: the terminating writer already set the terminal status, the actor, the reason and
             * the timestamp atomically, and re-saving this loop's stale copy would clobber those fields. Leaving
             * the document exactly as the terminate write left it is the whole point — the next node never runs.
             */
            execution.setStatus(ExecutionStatus.TERMINATED);
            context.logInfo(execution.getCurrentNodeId(), null, "Execution terminated", null);
            log.info("Execution {} terminated; stopping at node {}", execution.getId(),
                    execution.getCurrentNodeId());
            return Optional.of(execution);
        }
        if (status == ExecutionStatus.PAUSED) {
            /*
             * Likewise do not persist here. Whoever paused the instance already wrote PAUSED, and — for the
             * lifecycle pause — the pre-pause status and reason alongside it, atomically. Re-saving this loop's
             * copy (which knows none of that) would erase them. The loop simply stops; the status is already
             * durable.
             */
            execution.setStatus(ExecutionStatus.PAUSED);
            context.logInfo(execution.getCurrentNodeId(), null, "Execution paused", null);
            log.info("Execution {} paused at node {}", execution.getId(), execution.getCurrentNodeId());
            return Optional.of(execution);
        }
        return Optional.empty();
    }

    private WorkflowExecution cancel(WorkflowExecution execution, VariableStore store,
                                     WorkflowExecutionContext context, String reason) {
        execution.setStatus(ExecutionStatus.CANCELLED);
        execution.setCompletedAt(Instant.now());
        execution.setPendingSignal(null);
        persist(execution, store);
        context.logWarn(execution.getCurrentNodeId(), null, "Execution cancelled: " + reason, null);
        log.info("Execution {} cancelled: {}", execution.getId(), reason);
        eventPublisher.publishExecutionEvent(ExecutionLifecycleEvent.of(execution.getId(),
                execution.getWorkflowId(), execution.getWorkflowVersion(), ExecutionStatus.CANCELLED,
                execution.getCurrentNodeId()));
        return execution;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Decides where to start or resume.
     *
     * <p>A fresh execution starts at the single start node. A resumed one re-enters at the node that
     * parked or paused it, which is what makes a form submission continue the same run rather than
     * starting a new one.
     */
    private String resolveEntryNode(WorkflowExecution execution, WorkflowVersion definition,
                                    WorkflowExecutionContext context) {
        boolean resuming = execution.getStatus() == ExecutionStatus.WAITING
                || execution.getStatus() == ExecutionStatus.PAUSED
                || (execution.getStatus() == ExecutionStatus.RUNNING && execution.getStepCount() > 0);
        if (resuming && execution.getCurrentNodeId() != null) {
            if (definition.findNode(execution.getCurrentNodeId()) == null) {
                throw new IllegalStateException("Cannot resume: node '" + execution.getCurrentNodeId()
                        + "' is not part of workflow version " + execution.getWorkflowVersion());
            }
            return execution.getCurrentNodeId();
        }
        List<WorkflowNode> startNodes = context.graph().startNodes();
        if (startNodes.size() != 1) {
            throw new IllegalStateException("Workflow version " + execution.getWorkflowVersion()
                    + " must declare exactly one START node but declares " + startNodes.size());
        }
        return startNodes.get(0).getId();
    }

    private void seedSystemVariables(WorkflowExecution execution, WorkflowVersion definition,
                                     VariableStore store) {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("executionId", execution.getId());
        system.put("workflowId", execution.getWorkflowId());
        system.put("workflowVersion", execution.getWorkflowVersion());
        system.put("workflowName", definition.getName());
        system.put("mode", String.valueOf(execution.getMode()));
        system.put("instanceId", engineInstance.id());
        system.put("correlationId", execution.getCorrelationId());
        system.put("startedAt", execution.getStartedAt() == null
                ? Instant.now().toString() : execution.getStartedAt().toString());
        system.put("now", Instant.now().toString());

        /*
         * Identity of the user the execution runs on behalf of, carried in the system scope.
         *
         * Placed here rather than threaded through the execution context's constructor because the system
         * scope is already the engine's channel for facts about a run, is already persisted with the
         * execution, and is already what survives a resume after restart. A plugin reads it through
         * NodeExecutionContext.currentUser(); a workflow author can read ${system.username}.
         *
         * Only an id, a username and role names. Deliberately never the token that authenticated the
         * request: it is persisted with the execution and handed to plugin code, and neither is a place a
         * credential belongs.
         */
        if (execution.getTriggeredByUserId() != null) {
            system.put("userId", execution.getTriggeredByUserId());
        }
        if (execution.getTriggeredBy() != null) {
            system.put("username", execution.getTriggeredBy());
        }
        if (execution.getTriggeredByRoles() != null && !execution.getTriggeredByRoles().isEmpty()) {
            system.put("roles", List.copyOf(execution.getTriggeredByRoles()));
        }
        store.seed(VariableScope.SYSTEM, system);
    }

    private void applyOutputs(WorkflowNode node, NodeExecutionResult result, VariableStore store) {
        store.putNodeOutputs(node.getId(), result.outputs());
        variableMapper.applyOutputMapping(node.getOutputMapping(), result.outputs(), store);
    }

    private void recordNode(WorkflowExecution execution, WorkflowNode node, NodeExecutionResult result,
                            Instant startedAt, int attempt) {
        NodeExecutionRecord record = new NodeExecutionRecord();
        record.setNodeType(node.getType());
        record.setNodeName(node.getName());
        record.setStatus(result.status().name());
        record.setAttempt(attempt);
        record.setStartedAt(startedAt);
        record.setCompletedAt(Instant.now());
        record.setDurationMillis(Duration.between(startedAt, Instant.now()).toMillis());
        record.setSelectedBranch(result.selectedBranch());
        record.setErrorCode(result.errorCode());
        record.setErrorMessage(result.errorMessage());
        record.setPluginId(node.getPluginId());
        record.setPluginVersion(node.getPluginVersion());
        record.setOutputs(truncateOutputs(result.outputs()));
        execution.appendHistory(node.getId(), MAX_HISTORY_RECORDS, record);
    }

    /**
     * Keeps node history readable and the document small. A node that returns a megabyte of JSON should
     * not make the execution document unreadable, or unwritable once several such nodes have run.
     */
    private Map<String, Object> truncateOutputs(Map<String, Object> outputs) {
        Map<String, Object> copy = new LinkedHashMap<>();
        int budget = 32;
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            if (budget-- <= 0) {
                copy.put("__truncated", "output keys beyond the first 32 were not recorded");
                break;
            }
            Object value = entry.getValue();
            if (value instanceof CharSequence && ((CharSequence) value).length() > 2_000) {
                copy.put(entry.getKey(), ((CharSequence) value).subSequence(0, 2_000) + "...[truncated]");
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    /**
     * @return {@code false} when another writer owns the execution and this loop must stand down
     */
    private boolean persist(WorkflowExecution execution, VariableStore store) {
        execution.setVariables(store.snapshot());
        execution.setHeartbeatAt(Instant.now());
        try {
            WorkflowExecution saved = stateStore.save(execution);
            execution.setDocumentVersion(saved.getDocumentVersion());
            return true;
        } catch (OptimisticLockingFailureException ex) {
            log.warn("Execution {} was modified concurrently; this instance is standing down at node {}",
                    execution.getId(), execution.getCurrentNodeId());
            return false;
        }
    }

    private static long elapsedMillis(WorkflowExecution execution) {
        Instant from = execution.getStartedAt();
        Instant to = execution.getCompletedAt() == null ? Instant.now() : execution.getCompletedAt();
        return from == null ? 0 : Duration.between(from, to).toMillis();
    }
}
