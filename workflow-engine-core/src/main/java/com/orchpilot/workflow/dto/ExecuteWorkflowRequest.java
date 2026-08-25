package com.orchpilot.workflow.dto;

import java.util.Map;

/**
 * Payload for starting a workflow.
 *
 * @param input          values exposed to the workflow as {@code ${input.*}}
 * @param formData       submission for a form node reached immediately, letting a fully specified run
 *                       complete without parking
 * @param correlationId  caller-supplied id carried through logs and events
 * @param idempotencyKey makes {@code start} idempotent: repeating the same key returns the existing
 *                       execution instead of creating a second one. Use it when the caller may retry.
 * @param mode           {@code MANUAL} or {@code API}, for attribution only
 */
public record ExecuteWorkflowRequest(Map<String, Object> input, Map<String, Object> formData,
                                     String correlationId, String idempotencyKey, String mode) {

    /**
     * @return the input, never {@code null}
     */
    public Map<String, Object> safeInput() {
        return input == null ? Map.of() : input;
    }
}
