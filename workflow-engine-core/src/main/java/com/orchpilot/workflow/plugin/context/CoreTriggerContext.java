package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.event.WorkflowEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.trigger.TriggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a trigger plugin is given while it is running.
 *
 * <p>A trigger cannot start a workflow; it emits a named event and the engine starts whichever published
 * workflows subscribe to it. That indirection keeps trigger plugins unaware of workflows, versions and
 * execution modes, and means one mailbox poller can feed any number of workflows written later.
 *
 * <p>{@link #isRunning()} flips to false the moment the engine begins stopping the trigger, giving a polling
 * loop a way to exit promptly. A trigger that ignores it holds its thread, which holds the plugin's class
 * loader, which leaks it.
 */
public class CoreTriggerContext implements TriggerContext {

    private static final Logger log = LoggerFactory.getLogger(CoreTriggerContext.class);

    private final String coordinate;
    private final PluginContext pluginContext;
    private final WorkflowEventPublisher eventPublisher;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public CoreTriggerContext(String coordinate, PluginContext pluginContext,
                              WorkflowEventPublisher eventPublisher) {
        this.coordinate = coordinate;
        this.pluginContext = pluginContext;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void emit(String eventName, Map<String, Object> payload) {
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        if (!running.get()) {
            log.warn("Trigger {} emitted '{}' after being stopped; ignoring", coordinate, eventName);
            return;
        }
        eventPublisher.publishBusinessEvent(WorkflowEvent.of(eventName, payload, "trigger:" + coordinate));
        log.debug("Trigger {} emitted event '{}'", coordinate, eventName);
    }

    @Override
    public PluginContext pluginContext() {
        return pluginContext;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Signals the trigger to wind down. Called by the engine before {@code stop()}.
     */
    public void markStopping() {
        running.set(false);
    }
}
