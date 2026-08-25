package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.expression.ExpressionEvaluator;
import com.orchpilot.workflow.model.ExecutionMode;
import com.orchpilot.workflow.model.LogLevel;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.sdk.node.VariableView;
import com.orchpilot.workflow.variable.VariableMapper;
import com.orchpilot.workflow.variable.VariableResolver;
import com.orchpilot.workflow.variable.VariableStore;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Everything a node executor needs for the run it is part of.
 *
 * <p>This is the object that keeps node executors decoupled from the engine. An executor gets variable
 * access, configuration resolution, expression evaluation, logging and cancellation, and cannot reach
 * the repositories, the registry or the engine itself. That constraint is why a new built-in node type
 * is a single class with no wiring.
 *
 * <p>One instance per execution attempt, driven by one thread. The variable store and the cancellation
 * flag are individually thread-safe because the heartbeat writer and any plugin holding a
 * {@link VariableView} read them concurrently.
 */
public class WorkflowExecutionContext {

    private final WorkflowExecution execution;
    private final WorkflowVersion definition;
    private final WorkflowGraph graph;
    private final VariableStore variables;
    private final VariableResolver resolver;
    private final VariableMapper mapper;
    private final ExpressionEvaluator evaluator;
    private final ExecutionLogWriter logWriter;
    private final AtomicLong logSequence;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Map<String, Object>> pendingSignalData = new AtomicReference<>();
    private final AtomicReference<String> pendingSignalNodeId = new AtomicReference<>();
    private final Instant startedAt = Instant.now();
    private volatile int currentAttempt = 1;

    /**
     * @param execution   the execution document being advanced
     * @param definition  the pinned workflow version
     * @param variables   variable store, already rehydrated
     * @param resolver    placeholder resolver
     * @param mapper      input/output mapping applier
     * @param evaluator   expression evaluator
     * @param logWriter   execution log sink
     * @param logSequence next log sequence number for this execution
     */
    public WorkflowExecutionContext(WorkflowExecution execution, WorkflowVersion definition,
                                    VariableStore variables, VariableResolver resolver,
                                    VariableMapper mapper, ExpressionEvaluator evaluator,
                                    ExecutionLogWriter logWriter, long logSequence) {
        this.execution = execution;
        this.definition = definition;
        this.graph = WorkflowGraph.of(definition);
        this.variables = variables;
        this.resolver = resolver;
        this.mapper = mapper;
        this.evaluator = evaluator;
        this.logWriter = logWriter;
        this.logSequence = new AtomicLong(logSequence);
    }

    /** @return the execution document; callers must not change its status directly */
    public WorkflowExecution execution() {
        return execution;
    }

    /** @return the pinned workflow definition */
    public WorkflowVersion definition() {
        return definition;
    }

    /** @return indexed graph of the pinned definition */
    public WorkflowGraph graph() {
        return graph;
    }

    /** @return the execution's variables */
    public VariableStore variables() {
        return variables;
    }

    /** @return input/output mapping applier */
    public VariableMapper mapper() {
        return mapper;
    }

    public String executionId() {
        return execution.getId();
    }

    public String workflowId() {
        return execution.getWorkflowId();
    }

    public int workflowVersion() {
        return execution.getWorkflowVersion();
    }

    public ExecutionMode mode() {
        return execution.getMode();
    }

    /** @return when this run of the engine loop began */
    public Instant startedAt() {
        return startedAt;
    }

    /** @return 1-based attempt number of the node currently executing */
    public int currentAttempt() {
        return currentAttempt;
    }

    /**
     * @param attempt attempt number the retry template is about to make
     */
    void currentAttempt(int attempt) {
        this.currentAttempt = attempt;
    }

    /**
     * Resolves a node's configuration against the current variables.
     *
     * @param node node whose configuration to resolve
     * @return a fully resolved copy
     */
    public Map<String, Object> resolveConfiguration(WorkflowNode node) {
        return resolver.resolveConfiguration(node.getConfiguration(), variables);
    }

    /**
     * Resolves a node's configuration and reports any placeholder that referred to nothing.
     *
     * @param node the node
     * @return the resolved configuration together with anything unresolved
     */
    public com.orchpilot.workflow.variable.VariableResolver.Resolution resolveConfigurationReporting(
            WorkflowNode node) {
        return resolver.resolveConfigurationReporting(node.getConfiguration(), variables);
    }

    /**
     * Resolves a node's configuration, additionally allowing {@code ${SECRET.name}} references.
     *
     * @param node    the node
     * @param secrets supplies secret values — the caller's own scoped provider, so its grants apply
     * @return the resolved configuration and any unresolved references
     */
    public com.orchpilot.workflow.variable.VariableResolver.Resolution resolveConfigurationReporting(
            WorkflowNode node, com.orchpilot.workflow.variable.VariableResolver.SecretLookup secrets) {
        return resolver.resolveConfigurationReporting(node.getConfiguration(), variables, secrets);
    }

    /**
     * @param value map, list, string or scalar
     * @return a copy with placeholders resolved
     */
    public Object resolve(Object value) {
        return resolver.resolve(value, variables);
    }

    /**
     * @param template text possibly containing placeholders
     * @return the rendered text
     */
    public String resolveText(String template) {
        return resolver.resolveText(template, variables);
    }

    /**
     * @param expression boolean expression
     * @return the result
     * @throws com.orchpilot.workflow.exception.ExpressionEvaluationException when it cannot be evaluated
     */
    public boolean evaluateCondition(String expression) {
        return evaluator.evaluateBoolean(expression, variables.expressionRoot());
    }

    /**
     * @return a read-only variable view suitable for handing to a plugin
     */
    public VariableView variableView() {
        return new VariableView() {
            @Override
            public Optional<Object> find(String path) {
                return variables.find(path);
            }

            @Override
            public Map<String, Object> asMap() {
                return variables.snapshot();
            }
        };
    }

    /**
     * Supplies the data that will satisfy a parked node, consumed exactly once.
     *
     * @param nodeId node the signal is for
     * @param data   submitted values
     */
    public void offerSignal(String nodeId, Map<String, Object> data) {
        this.pendingSignalNodeId.set(nodeId);
        this.pendingSignalData.set(data);
    }

    /**
     * Takes the signal data for a node, if any.
     *
     * <p>Consuming rather than peeking is what stops a form node from immediately re-satisfying itself
     * if the execution loops back to it later in the same run.
     *
     * @param nodeId node asking for its signal
     * @return the submitted data, or empty
     */
    public Optional<Map<String, Object>> consumeSignal(String nodeId) {
        String target = pendingSignalNodeId.get();
        if (target == null || !target.equals(nodeId)) {
            return Optional.empty();
        }
        Map<String, Object> data = pendingSignalData.getAndSet(null);
        pendingSignalNodeId.set(null);
        return Optional.ofNullable(data);
    }

    /**
     * Requests cooperative cancellation. Nodes see it through {@link #isCancelled()} and the engine
     * stops at the next node boundary.
     */
    public void cancel() {
        cancelled.set(true);
    }

    /** @return whether cancellation has been requested */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * @param message log message
     * @param details structured context, may be {@code null}
     */
    public void logInfo(String nodeId, String nodeType, String message, Map<String, Object> details) {
        writeLog(LogLevel.INFO, nodeId, nodeType, message, details);
    }

    /**
     * @param message log message
     * @param details structured context, may be {@code null}
     */
    public void logWarn(String nodeId, String nodeType, String message, Map<String, Object> details) {
        writeLog(LogLevel.WARN, nodeId, nodeType, message, details);
    }

    /**
     * @param message log message
     * @param details structured context, may be {@code null}
     */
    public void logError(String nodeId, String nodeType, String message, Map<String, Object> details) {
        writeLog(LogLevel.ERROR, nodeId, nodeType, message, details);
    }

    /**
     * @param message log message
     * @param details structured context, may be {@code null}
     */
    public void logDebug(String nodeId, String nodeType, String message, Map<String, Object> details) {
        writeLog(LogLevel.DEBUG, nodeId, nodeType, message, details);
    }

    private void writeLog(LogLevel level, String nodeId, String nodeType, String message,
                          Map<String, Object> details) {
        logWriter.write(ExecutionLogWriter.entry(execution.getId(), logSequence.incrementAndGet(),
                level, nodeId, nodeType, message, details));
    }
}
