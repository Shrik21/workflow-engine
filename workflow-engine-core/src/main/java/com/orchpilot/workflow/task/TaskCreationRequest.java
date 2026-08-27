package com.orchpilot.workflow.task;

import java.util.Map;

/**
 * Everything needed to raise a task, assembled by the node executor.
 *
 * <p>A parameter object rather than a fourteen-argument method, and more importantly a boundary: the executor
 * has the execution context and computes the prefill from it, the service has the repositories and knows nothing
 * about the engine. Neither has to import the other's world.
 *
 * @param executionId      the execution parking here
 * @param workflowId       its workflow
 * @param workflowVersion  the pinned workflow version
 * @param workflowName     for display in an inbox row
 * @param nodeId           the node raising the task
 * @param nodeName         for display
 * @param formDefinitionId the form to render, or null when the node references no managed form
 * @param formVersion      the pinned form version
 * @param taskName         what the inbox row says, already resolved against the variables
 * @param description      longer text shown above the form
 * @param prefill          values to prefill, resolved from the execution's variables
 * @param assignment       who it goes to
 * @param createdBy        username that started the execution, or {@code system}
 * @param correlationId    the execution's correlation id, for tracing
 */
public record TaskCreationRequest(
        String executionId,
        String workflowId,
        int workflowVersion,
        String workflowName,
        String nodeId,
        String nodeName,
        String formDefinitionId,
        int formVersion,
        String taskName,
        String description,
        Map<String, Object> prefill,
        TaskAssignment assignment,
        String createdBy,
        String correlationId) {

    public TaskCreationRequest {
        prefill = prefill == null ? Map.of() : Map.copyOf(prefill);
    }
}
