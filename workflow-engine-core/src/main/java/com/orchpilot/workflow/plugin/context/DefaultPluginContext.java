package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.IdempotencyStore;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginEventPublisher;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.PluginSettings;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;

import java.nio.file.Path;

/**
 * The concrete set of services one plugin version receives.
 *
 * <p>Everything here is engine-owned and individually constrained. There is deliberately no accessor for
 * the Spring {@code ApplicationContext}, a {@code MongoTemplate}, the workflow repositories, the execution
 * engine or the plugin registry: a plugin that could reach those could start workflows behind the engine's
 * back, rewrite execution state, or read every other plugin's data.
 *
 * <p>One instance per loaded version, created at load time and handed to {@code initialize}. Plugins are
 * expected to retain it, which is why it must hold no per-execution state.
 */
public class DefaultPluginContext implements PluginContext {

    /**
     * Not final: node definitions only exist after {@code initialize} has run, so the descriptor handed to
     * {@code initialize} is completed immediately afterwards. Doing it this way rather than building a second
     * context matters, because a second context would carry a second {@link SecretRedactor} and the engine
     * would then redact using a different one than the plugin recorded its secrets in.
     */
    private volatile PluginDescriptor descriptor;

    private final PluginLogger logger;
    private final PluginSettings settings;
    private final SecretProvider secrets;
    private final PluginHttpClient http;
    private final PluginDataStore dataStore;
    private final IdempotencyStore idempotency;
    private final PluginEventPublisher events;
    private final Path workspace;
    private final SecretRedactor redactor;

    public DefaultPluginContext(PluginDescriptor descriptor, PluginLogger logger, PluginSettings settings,
                                SecretProvider secrets, PluginHttpClient http, PluginDataStore dataStore,
                                IdempotencyStore idempotency, PluginEventPublisher events, Path workspace,
                                SecretRedactor redactor) {
        this.descriptor = descriptor;
        this.logger = logger;
        this.settings = settings;
        this.secrets = secrets;
        this.http = http;
        this.dataStore = dataStore;
        this.idempotency = idempotency;
        this.events = events;
        this.workspace = workspace;
        this.redactor = redactor;
    }

    @Override
    public PluginDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public PluginLogger logger() {
        return logger;
    }

    @Override
    public PluginSettings settings() {
        return settings;
    }

    @Override
    public SecretProvider secrets() {
        return secrets;
    }

    @Override
    public PluginHttpClient http() {
        return http;
    }

    @Override
    public PluginDataStore dataStore() {
        return dataStore;
    }

    @Override
    public IdempotencyStore idempotency() {
        return idempotency;
    }

    @Override
    public PluginEventPublisher events() {
        return events;
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    /**
     * Not part of the SDK contract. Used by the engine to strip any secret this plugin has read out of
     * request and response records before they are persisted.
     *
     * @return the redactor tracking this plugin's secret reads
     */
    public SecretRedactor redactor() {
        return redactor;
    }

    /**
     * Completes the descriptor once the plugin's node definitions are known. Called by the plugin manager
     * immediately after {@code initialize}, before the version is registered, and never again.
     *
     * @param complete descriptor including node definitions
     */
    public void completeDescriptor(PluginDescriptor complete) {
        if (complete != null) {
            this.descriptor = complete;
        }
    }
}
