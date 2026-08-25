package com.orchpilot.workflow.sdk.plugin;

/**
 * Constants describing the plugin API contract itself.
 *
 * <p>The engine refuses to load a plugin whose {@link WorkflowPlugin#getApiVersion()} it does not
 * support, which is what allows the SDK to evolve without silently breaking deployed plugins.
 *
 * @since 1.0.0
 */
public final class PluginApi {

    /**
     * Current binary contract version of the plugin API.
     *
     * <p>Bump this only for breaking changes to the interfaces in this SDK. Additive changes
     * (new default methods, new value types) must not bump it.
     */
    public static final int VERSION = 1;

    /**
     * Lowest API version this engine generation still accepts.
     */
    public static final int MINIMUM_SUPPORTED_VERSION = 1;

    /**
     * Root package of the shared API. The engine's plugin class loaders delegate this package
     * parent-first so that a plugin and the engine always agree on these types.
     */
    public static final String SHARED_PACKAGE = "com.orchpilot.workflow.sdk";

    /**
     * Optional {@code MANIFEST.MF} attribute naming the {@link WorkflowPlugin} implementation.
     * Preferred over {@link java.util.ServiceLoader} discovery because it is explicit.
     */
    public static final String MANIFEST_PLUGIN_CLASS = "Workflow-Plugin-Class";

    /**
     * Optional {@code MANIFEST.MF} attribute declaring the API version the plugin was built for.
     */
    public static final String MANIFEST_API_VERSION = "Workflow-Plugin-Api-Version";

    private PluginApi() {
    }
}
