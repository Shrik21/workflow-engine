package com.orchpilot.workflow.sdk.node;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginLogger;

import java.time.Instant;

/**
 * Everything a plugin node is given for one execution attempt.
 *
 * <p>Scoped deliberately narrowly: identifiers, resolved configuration, read-only variables, and
 * the plugin's own {@link PluginContext}. It exposes no engine services, no Spring beans and no
 * mutable execution state, so the engine's internals can change freely underneath it.
 *
 * <p>An instance belongs to a single attempt on a single thread and must not be retained after
 * {@link com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin#execute} returns.
 *
 * @since 1.0.0
 */
public interface NodeExecutionContext {

    /** @return id of the workflow execution this attempt belongs to */
    String executionId();

    /** @return id of the workflow being executed */
    String workflowId();

    /** @return pinned version of the workflow definition being executed */
    int workflowVersion();

    /** @return id of the node being executed, unique within the workflow */
    String nodeId();

    /** @return node type being executed, e.g. {@code SENDGRID_EMAIL} */
    String nodeType();

    /** @return 1-based attempt number; greater than 1 means this is a retry */
    int attempt();

    /**
     * Deterministic key for the side effect this node is about to perform. Stable across retries
     * and across resume-after-restart for the same execution, node and configuration.
     *
     * @return idempotency key suitable for passing to a downstream provider
     */
    String idempotencyKey();

    /** @return the node's configuration with all variable placeholders already resolved */
    NodeConfiguration configuration();

    /** @return read-only view of the execution's variables */
    VariableView variables();

    /**
     * Resolves {@code ${...}} placeholders in an arbitrary string against the current variables.
     * Configuration is already resolved; use this only for text a plugin composes itself.
     *
     * @param template text possibly containing placeholders
     * @return the resolved text, or {@code null} when {@code template} is {@code null}
     */
    String resolve(String template);

    /** @return the plugin's own context: logger, secrets, HTTP client, data store, events */
    PluginContext pluginContext();

    /**
     * Files attached to the workflow version being executed.
     *
     * <p>The returned accessor is already bound to this execution's workflow and version, so a plugin cannot
     * name another workflow's file — see
     * {@link com.orchpilot.workflow.sdk.context.WorkflowFileAccess} for why that is the whole point.
     *
     * <p>A {@code default} rather than an abstract method so that adding it did not break the plugins already
     * compiled against this SDK: they keep working untouched, and only a plugin that actually calls it needs an
     * engine that supplies one. An engine that does not throws here rather than returning null, because a null
     * would surface later as an unexplained {@code NullPointerException} inside the plugin.
     *
     * <p>Availability is <strong>not</strong> expressible as a version range: this arrived without a version
     * bump, so an engine either overrides this method or it does not, and both call themselves 1.0.0. A plugin
     * needing files should therefore declare the ordinary {@code >=1.0.0} compatibility and rely on catching
     * the exception below — a manifest range cannot detect it.
     *
     * @return the file accessor for this execution
     * @throws UnsupportedOperationException when the running engine does not supply file access
     * @since 1.0.0
     */
    default com.orchpilot.workflow.sdk.context.WorkflowFileAccess files() {
        throw new UnsupportedOperationException(
                "This OrchPilot engine does not provide workflow file access to plugins. "
                        + "Upgrade the engine to use a plugin that reads or writes workflow files.");
    }

    /**
     * The user this execution is running on behalf of.
     *
     * <p>Empty for a scheduled or event-triggered execution, which has no user, so a plugin must handle
     * absence rather than assume one. Carries only an id, a username and role names: see
     * {@link WorkflowUser} for why it can carry nothing else.
     *
     * <p>A default method returning empty, so an implementation compiled against an earlier SDK still
     * satisfies this interface.
     *
     * @return the acting user, or empty when the execution was started by the engine itself
     * @since 1.0.0
     */
    default java.util.Optional<WorkflowUser> currentUser() {
        return java.util.Optional.empty();
    }

    /** @return convenience accessor for {@code pluginContext().logger()} */
    default PluginLogger logger() {
        return pluginContext().logger();
    }

    /**
     * Cooperative cancellation. Long-running plugins should poll this and return promptly.
     *
     * @return {@code true} when the execution has been cancelled or is being drained
     */
    boolean isCancelled();

    /** @return when this attempt started */
    Instant startedAt();

    /**
     * @return wall-clock budget in milliseconds for this attempt, or {@code 0} when unbounded;
     *         plugins should use it as their own I/O timeout ceiling
     */
    long timeoutMillis();
}
