package com.orchpilot.workflow.sdk.trigger;

import com.orchpilot.workflow.sdk.context.PluginContext;

import java.util.Map;

/**
 * Handed to a {@link com.orchpilot.workflow.sdk.plugin.TriggerPlugin} when it starts, giving it the one
 * capability a trigger needs: emitting events into the engine.
 *
 * <p>A trigger cannot start a workflow directly. It emits a named event, and the engine starts every
 * published workflow subscribed to that event. That indirection is what keeps trigger plugins
 * unaware of workflows, versions and execution modes.
 *
 * @since 1.0.0
 */
public interface TriggerContext {

    /**
     * Emits an event, starting every published workflow subscribed to it.
     *
     * @param eventName stable event name, e.g. {@code FILE_UPLOADED}
     * @param payload   event payload, exposed to workflows as {@code ${input.*}}; must not contain
     *                  secrets
     */
    void emit(String eventName, Map<String, Object> payload);

    /** @return the plugin's own context: logger, secrets, HTTP client, data store */
    PluginContext pluginContext();

    /**
     * @return {@code false} once the engine has begun stopping this trigger; polling loops must exit
     *         promptly when this turns false
     */
    boolean isRunning();
}
