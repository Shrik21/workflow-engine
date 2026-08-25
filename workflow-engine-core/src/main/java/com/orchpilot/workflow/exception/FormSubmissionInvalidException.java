package com.orchpilot.workflow.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A form submission failed server-side validation.
 *
 * <p>Distinct from {@link WorkflowValidationException} because the two are answers to different people. That one
 * tells an author their graph is wrong; this one tells somebody filling in a form which of their fields is wrong,
 * and its message is written to be shown to them.
 *
 * <p>Carries every problem, flattened to {@code field: message} strings so the existing {@code ApiError.details}
 * shape needs no change, and keeps the per-field map for a client that wants to attach messages to controls.
 */
public class FormSubmissionInvalidException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    private final Map<String, List<String>> problems;

    /**
     * @param problems problems by field name, as returned by the validation service
     */
    public FormSubmissionInvalidException(Map<String, List<String>> problems) {
        super("FORM_SUBMISSION_INVALID", problems.size() == 1
                ? "One field needs attention before this can be submitted"
                : problems.size() + " fields need attention before this can be submitted");
        this.problems = Map.copyOf(problems);
    }

    /** @return problems by field name */
    public Map<String, List<String>> getProblems() {
        return problems;
    }

    /** @return every problem as {@code field: message}, for the error response's details list */
    public List<String> getErrors() {
        List<String> flattened = new ArrayList<>();
        problems.forEach((field, messages) -> messages.forEach(message ->
                flattened.add(field + ": " + message)));
        return flattened;
    }
}
