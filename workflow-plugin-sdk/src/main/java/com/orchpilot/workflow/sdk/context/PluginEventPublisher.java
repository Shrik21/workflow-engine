package com.orchpilot.workflow.sdk.context;

import java.util.Map;

/**
 * Lets a plugin emit a named business event into the engine's event bus, where it can start
 * event-triggered workflows or be consumed by other plugins.
 *
 * <p>Publication is fire-and-forget and never fails the calling node.
 *
 * @since 1.0.0
 */
public interface PluginEventPublisher {

    /**
     * @param eventName stable event name, e.g. {@code ORDER_CREATED}
     * @param payload   event payload; must not contain secrets, may be {@code null}
     */
    void publish(String eventName, Map<String, Object> payload);
}
