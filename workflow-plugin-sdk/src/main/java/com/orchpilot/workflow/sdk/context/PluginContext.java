package com.orchpilot.workflow.sdk.context;

import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;

import java.nio.file.Path;

/**
 * The complete set of engine services a plugin is allowed to use, handed to it once at
 * {@code initialize} time.
 *
 * <p>This interface is the security and coupling boundary of the whole platform. It deliberately
 * does <strong>not</strong> expose the Spring {@code ApplicationContext}, a {@code MongoTemplate},
 * engine repositories, the {@code WorkflowExecutionEngine} or the {@code PluginRegistry}. A plugin
 * therefore cannot start workflows behind the engine's back, mutate execution state directly, read
 * another plugin's data or reconfigure the application.
 *
 * <p>Every accessor returns a stable, engine-owned object that remains valid for the lifetime of the
 * plugin version. After the engine unloads the plugin, using a retained context is undefined; do not
 * hand it to threads that outlive {@code destroy()}.
 *
 * @since 1.0.0
 */
public interface PluginContext {

    /** @return identity of this plugin version, including its contributed node types */
    PluginDescriptor descriptor();

    /** @return logger pre-tagged with the plugin id and version */
    PluginLogger logger();

    /** @return installation-scoped, non-secret settings for this plugin version */
    PluginSettings settings();

    /** @return scoped, audited access to credentials */
    SecretProvider secrets();

    /** @return engine-owned HTTP client with allowlist and timeout enforcement */
    PluginHttpClient http();

    /** @return namespaced document storage private to this plugin */
    PluginDataStore dataStore();

    /** @return deduplication store for plugins with external side effects */
    IdempotencyStore idempotency();

    /** @return publisher for named business events */
    PluginEventPublisher events();

    /**
     * Scratch directory private to this plugin version, deleted when the version is unloaded.
     * Use it for temporary files; never for state that must survive a reload.
     *
     * @return an existing, writable directory
     */
    Path workspace();
}
