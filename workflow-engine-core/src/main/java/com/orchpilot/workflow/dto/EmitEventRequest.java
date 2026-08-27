package com.orchpilot.workflow.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Payload for emitting a business event.
 *
 * <p>Emitting an event starts every published workflow subscribed to it, so the payload becomes the input of
 * each started execution.
 *
 * @param name          event name, e.g. {@code ORDER_CREATED}
 * @param payload       event data, exposed to workflows as {@code ${input.*}}
 * @param correlationId caller-supplied id carried into every started execution
 */
public record EmitEventRequest(@NotBlank(message = "name is required") String name,
                               Map<String, Object> payload, String correlationId) {
}
