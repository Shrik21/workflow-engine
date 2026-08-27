package com.orchpilot.workflow.sdk.plugin;

import com.orchpilot.workflow.sdk.trigger.TriggerContext;

/**
 * A plugin that watches something external and emits events that start workflows: a message queue
 * consumer, a mailbox poller, a filesystem watcher, a webhook bridge.
 *
 * <p>Lifecycle, in addition to {@link WorkflowPlugin}'s:
 * <pre>
 * initialize(context) → start(triggerContext) → [emit … ] → stop() → destroy()
 * </pre>
 *
 * <p>{@code start} must not block: spawn a thread and return. Every thread spawned must be stopped
 * in {@code stop}, otherwise the plugin's class loader can never be collected and hot reload leaks
 * memory on every cycle. Name your threads after the plugin id so leaks are diagnosable.
 *
 * @since 1.0.0
 */
public interface TriggerPlugin extends WorkflowPlugin {

    /**
     * Starts listening. Must return promptly; do the waiting on your own thread.
     *
     * @param context sink for emitted events, plus the plugin's own services
     */
    void start(TriggerContext context);

    /**
     * Stops listening and joins any thread {@link #start(TriggerContext)} spawned. Called before
     * {@link WorkflowPlugin#destroy()} and must not throw.
     */
    void stop();
}
