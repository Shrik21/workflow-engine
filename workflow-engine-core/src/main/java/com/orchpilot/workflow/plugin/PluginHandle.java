package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.plugin.WorkflowPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One loaded plugin version: its instance, its class loader, its metadata, and the lease counter that
 * makes unloading safe.
 *
 * <p><b>The lease counter is the important part.</b> Unloading a plugin while a workflow is inside its
 * {@code execute} method would produce a {@code NoClassDefFoundError} halfway through sending an email.
 * Every plugin invocation must therefore acquire a lease first, and unloading waits for the count to
 * reach zero. {@link #beginDraining()} closes the door before the wait begins, so the count is
 * guaranteed to fall rather than being topped up by new arrivals.
 *
 * <p>This handle is also the single strong reference to the plugin instance and its class loader.
 * Dropping it from the registry, after {@code destroy()} and {@code close()}, is what allows the loader
 * to be garbage collected. Nothing else in the engine may cache a plugin class or instance, or the
 * loader leaks on every reload.
 */
public final class PluginHandle {

    private static final Logger log = LoggerFactory.getLogger(PluginHandle.class);

    private final PluginDescriptor descriptor;
    private final WorkflowPlugin instance;
    private final PluginClassLoader classLoader;
    private final com.orchpilot.workflow.plugin.context.DefaultPluginContext pluginContext;
    private final PluginVersion versionMetadata;
    private final Path workspace;
    private final java.util.List<com.orchpilot.workflow.sdk.node.NodeDefinition> nodeDefinitions;
    private final java.util.List<String> nodeTypes;
    private final Instant loadedAt = Instant.now();

    private final AtomicInteger activeLeases = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicLong totalInvocations = new AtomicLong();
    private final AtomicLong failedInvocations = new AtomicLong();

    private volatile PluginState state = PluginState.LOADING;

    public PluginHandle(PluginDescriptor descriptor, WorkflowPlugin instance,
                        PluginClassLoader classLoader,
                        com.orchpilot.workflow.plugin.context.DefaultPluginContext pluginContext,
                        PluginVersion versionMetadata, Path workspace) {
        this.descriptor = descriptor;
        this.instance = instance;
        this.classLoader = classLoader;
        this.pluginContext = pluginContext;
        this.versionMetadata = versionMetadata;
        this.workspace = workspace;
        // Captured once at load time. The SDK contract says a plugin's node definitions are fixed after
        // initialisation, and caching them here means the engine never calls back into plugin code just
        // to answer a registry question.
        this.nodeDefinitions = java.util.List.copyOf(descriptor.nodeDefinitions());
        this.nodeTypes = descriptor.nodeDefinitions().stream()
                .map(com.orchpilot.workflow.sdk.node.NodeDefinition::nodeType)
                .toList();
    }

    /** @return immutable node definitions captured when the version was loaded */
    public java.util.List<com.orchpilot.workflow.sdk.node.NodeDefinition> nodeDefinitions() {
        return nodeDefinitions;
    }

    /** @return immutable node type names captured when the version was loaded */
    public java.util.List<String> nodeTypes() {
        return nodeTypes;
    }

    /**
     * @param nodeType node type to look up
     * @return the definition, or empty when this version does not contribute it
     */
    public Optional<com.orchpilot.workflow.sdk.node.NodeDefinition> nodeDefinition(String nodeType) {
        if (nodeType == null) {
            return Optional.empty();
        }
        for (com.orchpilot.workflow.sdk.node.NodeDefinition definition : nodeDefinitions) {
            if (nodeType.equals(definition.nodeType())) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }

    public PluginDescriptor descriptor() {
        return descriptor;
    }

    public WorkflowPlugin instance() {
        return instance;
    }

    /**
     * @return the plugin as a node plugin, or empty when it contributes no node types
     */
    public Optional<WorkflowNodePlugin> asNodePlugin() {
        return instance instanceof WorkflowNodePlugin
                ? Optional.of((WorkflowNodePlugin) instance)
                : Optional.empty();
    }

    public PluginClassLoader classLoader() {
        return classLoader;
    }

    /**
     * @return the services this version was given, including the redactor the engine uses to strip any
     *         secret the plugin has read out of persisted request and response records
     */
    public com.orchpilot.workflow.plugin.context.DefaultPluginContext pluginContext() {
        return pluginContext;
    }

    /** @return the persisted metadata this version was loaded from, including permissions and settings */
    public PluginVersion versionMetadata() {
        return versionMetadata;
    }

    public Path workspace() {
        return workspace;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public String pluginId() {
        return descriptor.id();
    }

    public String version() {
        return descriptor.version();
    }

    /** @return {@code pluginId:version} */
    public String coordinate() {
        return descriptor.coordinate();
    }

    public PluginState state() {
        return state;
    }

    void state(PluginState newState) {
        this.state = newState;
    }

    /**
     * Reserves the plugin for one invocation.
     *
     * <p>The flag is re-checked after incrementing: a lease granted in the instant draining began would
     * otherwise slip through and be invisible to the drain wait.
     *
     * @return {@code true} when the caller holds a lease and must call {@link #releaseLease()}
     */
    public boolean tryAcquireLease() {
        if (draining.get() || state != PluginState.ACTIVE) {
            return false;
        }
        activeLeases.incrementAndGet();
        if (draining.get() || state != PluginState.ACTIVE) {
            activeLeases.decrementAndGet();
            return false;
        }
        totalInvocations.incrementAndGet();
        return true;
    }

    /**
     * Releases a lease taken by {@link #tryAcquireLease()}.
     */
    public void releaseLease() {
        int remaining = activeLeases.decrementAndGet();
        if (remaining < 0) {
            // A double release is a bug in the engine, not the plugin. Correct it and say so loudly.
            activeLeases.set(0);
            log.error("Lease counter for plugin {} went negative; this indicates an unbalanced release",
                    coordinate());
        }
    }

    /** Records that an invocation failed, for operational visibility. */
    public void recordFailure() {
        failedInvocations.incrementAndGet();
    }

    /**
     * Closes the door to new leases.
     *
     * @return {@code true} when this call started draining, {@code false} when it was already draining
     */
    public boolean beginDraining() {
        boolean started = draining.compareAndSet(false, true);
        if (started) {
            state = PluginState.DRAINING;
            log.info("Plugin {} draining with {} in-flight invocation(s)", coordinate(), activeLeases.get());
        }
        return started;
    }

    /**
     * Waits for in-flight invocations to finish.
     *
     * @param timeoutMillis how long to wait
     * @return {@code true} when the plugin became quiescent within the timeout
     */
    public boolean awaitQuiescence(long timeoutMillis) {
        long deadline = System.nanoTime() + Math.max(0, timeoutMillis) * 1_000_000L;
        while (activeLeases.get() > 0) {
            if (System.nanoTime() >= deadline) {
                log.warn("Plugin {} still has {} in-flight invocation(s) after {} ms",
                        coordinate(), activeLeases.get(), timeoutMillis);
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return activeLeases.get() == 0;
            }
        }
        return true;
    }

    public int activeLeaseCount() {
        return activeLeases.get();
    }

    public long totalInvocations() {
        return totalInvocations.get();
    }

    public long failedInvocations() {
        return failedInvocations.get();
    }

    public boolean isDraining() {
        return draining.get();
    }

    @Override
    public String toString() {
        return "PluginHandle{" + coordinate() + ", state=" + state + ", leases=" + activeLeases.get() + "}";
    }
}
