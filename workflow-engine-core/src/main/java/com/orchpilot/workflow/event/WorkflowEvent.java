package com.orchpilot.workflow.event;

import java.time.Instant;
import java.util.Map;

/**
 * A named business event that can start event-triggered workflows.
 *
 * <p>Emitted by {@code POST /api/events}, by trigger plugins, and by workflows themselves. The engine
 * never inspects the payload; it matches on {@link #name()} and hands the payload to the started
 * execution as its input.
 *
 * @param name          stable event name, e.g. {@code ORDER_CREATED}
 * @param payload       event data, exposed to workflows as {@code ${input.*}}
 * @param source        who emitted it, for attribution
 * @param correlationId caller-supplied id carried into every started execution
 * @param at            emission time
 */
public record WorkflowEvent(String name, Map<String, Object> payload, String source, String correlationId,
                            Instant at) {

    /**
     * @param name    event name
     * @param payload event data, may be {@code null}
     * @param source  emitter
     * @return an event stamped with the current time
     */
    public static WorkflowEvent of(String name, Map<String, Object> payload, String source) {
        return new WorkflowEvent(name, payload == null ? Map.of() : Map.copyOf(payload), source, null,
                Instant.now());
    }
}
