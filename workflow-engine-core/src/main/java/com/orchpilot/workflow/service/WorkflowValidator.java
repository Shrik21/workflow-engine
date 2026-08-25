package com.orchpilot.workflow.service;

import com.orchpilot.workflow.model.Workflow;

import java.util.List;

/**
 * Checks that a workflow is executable before it is published.
 *
 * <p>Publishing is the right moment for this. Validating on every save would fight the author while they are
 * still building the graph; validating at execution time would surface the problem to whoever triggered the
 * run rather than to whoever broke it.
 */
public interface WorkflowValidator {

    /**
     * @param workflow workflow to check
     * @return every problem found, empty when the workflow is publishable
     */
    List<String> validate(Workflow workflow);

    /**
     * @param workflow workflow to check
     * @return problems that are warnings rather than blockers, such as a cycle in the graph
     */
    List<String> warnings(Workflow workflow);
}
