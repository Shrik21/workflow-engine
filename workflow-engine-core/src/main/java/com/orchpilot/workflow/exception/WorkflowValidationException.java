package com.orchpilot.workflow.exception;

import java.util.List;

/**
 * A workflow failed validation.
 *
 * <p>Carries every problem found rather than the first, because an author fixing a graph wants the
 * whole list, not one error per round trip.
 */
public class WorkflowValidationException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    public WorkflowValidationException(String workflowId, List<String> errors) {
        super("WORKFLOW_INVALID",
                "Workflow '" + workflowId + "' is not valid: " + errors.size() + " problem(s) found");
        this.errors = List.copyOf(errors);
    }

    /** @return every validation problem found, in discovery order */
    public List<String> getErrors() {
        return errors;
    }
}
