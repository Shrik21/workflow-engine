package com.orchpilot.workflow.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Request bodies for the task API. */
public final class TaskRequests {

    private TaskRequests() {
    }

    /**
     * A submission, or a saved draft.
     *
     * <p>Carries values and nothing else. No user id, no node id, no field-to-variable mapping: the task id in
     * the path identifies the work, the authenticated principal identifies the person, and the server holds the
     * mapping. A client that sent {@code {"userId": "somebody-else"}} would be sending a field that is not read.
     *
     * @param formData the field values, keyed by field name
     */
    public record Submission(Map<String, Object> formData) {

        /** @return the values, never null */
        public Map<String, Object> safeData() {
            return formData == null ? Map.of() : formData;
        }
    }

    /**
     * Moving a task to somebody else.
     *
     * @param assignee the new holder's username or user id
     * @param comment  why, recorded in the history
     */
    public record Reassignment(@NotBlank(message = "an assignee is required") String assignee,
                               @Size(max = 500, message = "must be at most 500 characters") String comment) {
    }

    /**
     * Withdrawing a task.
     *
     * @param reason why, recorded in the history and the audit trail
     */
    public record Cancellation(@Size(max = 500, message = "must be at most 500 characters") String reason) {
    }
}
