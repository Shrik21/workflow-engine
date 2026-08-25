package com.orchpilot.workflow.sdk.plugin;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginException;

/**
 * Root contract every workflow plugin implements.
 *
 * <p>This interface carries identity and lifecycle only. Execution semantics live in the
 * sub-interfaces {@link WorkflowNodePlugin}, {@link ActionPlugin} and {@link TriggerPlugin}, which
 * keeps a trigger from having to implement a node's {@code execute} method and vice versa.
 *
 * <h2>Discovery</h2>
 * The engine finds the implementation class in this order:
 * <ol>
 *   <li>the {@code Workflow-Plugin-Class} attribute in the JAR manifest (preferred);</li>
 *   <li>a {@link java.util.ServiceLoader} entry in
 *       {@code META-INF/services/com.orchpilot.workflow.sdk.plugin.WorkflowPlugin};</li>
 *   <li>an explicit {@code mainClass} supplied with the upload request.</li>
 * </ol>
 * The class must be public, concrete and have a public no-argument constructor.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 * instantiate → initialize(context) → [execute … many times, concurrently] → destroy()
 * </pre>
 * {@code initialize} and {@code destroy} are called exactly once per loaded version and are never
 * concurrent with each other. Execution methods <strong>are</strong> called concurrently from many
 * workflow executions, so implementations must be thread-safe and must not keep per-execution state
 * in instance fields.
 *
 * <h2>Threading and resources</h2>
 * Anything a plugin allocates in {@code initialize} must be released in {@code destroy}: threads,
 * connection pools, timers, watchers. A thread left running after {@code destroy} pins the plugin's
 * class loader and leaks it permanently.
 *
 * @since 1.0.0
 */
public interface WorkflowPlugin {

    /**
     * Stable plugin identifier, unchanged across versions, lower-kebab-case by convention.
     * Workflows reference this together with a version.
     *
     * @return plugin id, e.g. {@code sendgrid}
     */
    String getId();

    /** @return human-readable plugin name for administration screens */
    String getName();

    /**
     * @return semantic version of this build, e.g. {@code 1.1.0}; must match the version supplied at
     *         upload time
     */
    String getVersion();

    /** @return human-readable description of what the plugin does */
    String getDescription();

    /** @return extension category, which determines the sub-interface the engine expects */
    PluginType getPluginType();

    /**
     * @return plugin API version this implementation was built against; the engine refuses to load
     *         plugins outside the range it supports
     */
    default int getApiVersion() {
        return PluginApi.VERSION;
    }

    /**
     * Called once after the plugin is instantiated in its own class loader and before any execution.
     * Validate settings, build clients, and fail fast here rather than on first use.
     *
     * @param context the only engine services this plugin may use; safe to retain in a field
     * @throws PluginException when the plugin cannot operate; the engine aborts the load, records
     *                         the failure and leaves the version inactive
     */
    void initialize(PluginContext context) throws PluginException;

    /**
     * Called once when the version is being unloaded, after the engine has drained in-flight
     * executions. Must release every resource the plugin allocated, and must not throw; the engine
     * logs and continues regardless so that a badly behaved plugin cannot block unloading.
     */
    void destroy();
}
