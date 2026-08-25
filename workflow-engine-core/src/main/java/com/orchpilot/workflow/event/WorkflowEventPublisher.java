package com.orchpilot.workflow.event;

/**
 * Outbound event bus of the engine.
 *
 * <p>An interface so that publication can move from in-process Spring events to Kafka or an outbox
 * table without touching the engine, the node executors or any plugin.
 *
 * <p>Publication must never fail the caller. A listener that throws is the listener's problem, not the
 * workflow's.
 */
public interface WorkflowEventPublisher {

    /**
     * @param event named business event; may start event-triggered workflows
     */
    void publishBusinessEvent(WorkflowEvent event);

    /**
     * @param event execution state transition
     */
    void publishExecutionEvent(ExecutionLifecycleEvent event);

    /**
     * @param event plugin lifecycle transition
     */
    void publishPluginEvent(PluginLifecycleEvent event);
}
