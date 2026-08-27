package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.WorkflowFileAccess;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.VariableView;

import java.time.Instant;
import java.util.Map;

/**
 * Adapts the engine's internal execution context to the narrow, stable
 * {@link NodeExecutionContext} a plugin sees.
 *
 * <p>This adapter is what lets the engine's internals change freely. A plugin compiled against version 1 of
 * the SDK keeps working when {@code WorkflowExecutionContext} gains fields, changes how variables are
 * stored, or moves persistence elsewhere, because none of that is visible through this interface.
 *
 * <p>One instance per attempt. It holds the resolved configuration for that attempt, so a plugin that
 * retains it past {@code execute} sees stale data; the SDK documents that as unsupported.
 */
public class PluginNodeExecutionContext implements NodeExecutionContext {

    private final WorkflowExecutionContext delegate;
    private final WorkflowNode node;
    private final String nodeType;
    private final NodeConfiguration configuration;
    private final PluginContext pluginContext;
    private final String idempotencyKey;
    private final int attempt;
    private final long timeoutMillis;
    private final WorkflowFileAccess files;
    private final Instant startedAt = Instant.now();

    public PluginNodeExecutionContext(WorkflowExecutionContext delegate, WorkflowNode node, String nodeType,
                                      Map<String, Object> resolvedConfiguration, PluginContext pluginContext,
                                      String idempotencyKey, int attempt, long timeoutMillis,
                                      WorkflowFileAccess files) {
        this.delegate = delegate;
        this.node = node;
        this.nodeType = nodeType;
        this.configuration = new MapNodeConfiguration(resolvedConfiguration);
        this.pluginContext = pluginContext;
        this.idempotencyKey = idempotencyKey;
        this.attempt = attempt;
        this.timeoutMillis = timeoutMillis;
        this.files = files;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Already bound to this execution's workflow and version, so a plugin has no way to ask for another
     * workflow's file.
     */
    @Override
    public WorkflowFileAccess files() {
        return files;
    }

    /**
     * The acting user, read from the execution's system scope.
     *
     * <p>Reconstructed from variables rather than held as a field, so it survives a resume after restart:
     * the system scope is persisted with the execution, whereas anything passed only at construction time
     * would be lost when the engine reloads a parked run.
     *
     * <p>Empty for a scheduled or event-triggered execution, which genuinely has no user.
     */
    @Override
    public java.util.Optional<com.orchpilot.workflow.sdk.node.WorkflowUser> currentUser() {
        VariableView variables = variables();
        String userId = variables.getString("system.userId");
        if (userId == null || userId.isBlank()) {
            return java.util.Optional.empty();
        }
        Object roles = variables.find("system.roles").orElse(null);
        java.util.Set<String> roleNames = new java.util.LinkedHashSet<>();
        if (roles instanceof Iterable<?> iterable) {
            for (Object role : iterable) {
                if (role != null) {
                    roleNames.add(String.valueOf(role));
                }
            }
        }
        return java.util.Optional.of(new com.orchpilot.workflow.sdk.node.WorkflowUser(
                userId, variables.getString("system.username"), roleNames));
    }

    @Override
    public String executionId() {
        return delegate.executionId();
    }

    @Override
    public String workflowId() {
        return delegate.workflowId();
    }

    @Override
    public int workflowVersion() {
        return delegate.workflowVersion();
    }

    @Override
    public String nodeId() {
        return node.getId();
    }

    @Override
    public String nodeType() {
        return nodeType;
    }

    @Override
    public int attempt() {
        return attempt;
    }

    @Override
    public String idempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public NodeConfiguration configuration() {
        return configuration;
    }

    @Override
    public VariableView variables() {
        return delegate.variableView();
    }

    @Override
    public String resolve(String template) {
        return delegate.resolveText(template);
    }

    @Override
    public PluginContext pluginContext() {
        return pluginContext;
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public Instant startedAt() {
        return startedAt;
    }

    @Override
    public long timeoutMillis() {
        return timeoutMillis;
    }
}
