package com.orchpilot.workflow.dto;

import java.util.Map;

/**
 * Payload that satisfies a parked form node and resumes the execution.
 *
 * @param nodeId   node the submission is for; optional, and validated against the parked node when supplied
 * @param formId   form the submission is for; optional, and validated the same way
 * @param data     submitted field values
 * @param async    when true the resumed execution runs on the engine's pool and the call returns immediately
 */
public record FormSubmissionRequest(String nodeId, String formId, Map<String, Object> data, Boolean async) {

    /**
     * @return the submitted data, never {@code null}
     */
    public Map<String, Object> safeData() {
        return data == null ? Map.of() : data;
    }
}
