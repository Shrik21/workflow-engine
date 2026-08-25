package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.event.WorkflowEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.sdk.context.PluginEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Lets a plugin emit business events, if its permissions allow it.
 *
 * <p>Events can start other workflows, so this is a capability worth gating: a plugin that could emit
 * arbitrary events could start any event-triggered workflow in the system. Publication is fire-and-forget
 * and never fails the calling node, and the source is stamped with the plugin coordinate so a runaway
 * event loop is traceable to its origin.
 */
public class CorePluginEventPublisher implements PluginEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CorePluginEventPublisher.class);

    private final String coordinate;
    private final WorkflowEventPublisher delegate;
    private final boolean enabled;

    /**
     * @param coordinate {@code pluginId:version}, stamped on emitted events
     * @param delegate   engine event bus
     * @param enabled    whether this plugin version may publish events
     */
    public CorePluginEventPublisher(String coordinate, WorkflowEventPublisher delegate, boolean enabled) {
        this.coordinate = coordinate;
        this.delegate = delegate;
        this.enabled = enabled;
    }

    @Override
    public void publish(String eventName, Map<String, Object> payload) {
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        if (!enabled) {
            log.warn("Plugin {} attempted to publish event '{}' but event publication is not permitted for "
                    + "this version", coordinate, eventName);
            return;
        }
        delegate.publishBusinessEvent(WorkflowEvent.of(eventName, payload, "plugin:" + coordinate));
        log.debug("Plugin {} published event '{}'", coordinate, eventName);
    }
}
