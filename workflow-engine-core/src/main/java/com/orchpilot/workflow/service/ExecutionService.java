package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.FormSubmissionRequest;
import com.orchpilot.workflow.model.ExecutionLogEntry;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Starting, inspecting and steering executions.
 *
 * <p>Every method here ends up calling the same {@code WorkflowExecutionEngine}. This layer owns the questions
 * the engine should not have to answer: which version to pin, whether an equivalent execution already exists,
 * whether the requested transition is legal, and whether to run on the caller's thread or a pool thread.
 */
public interface ExecutionService {

    /**
     * Starts an execution.
     *
     * <p>When the command carries an idempotency key that has been seen before, the existing execution is
     * returned unchanged rather than a second one being created.
     *
     * @param command what to run and how
     * @return the execution, completed when synchronous, or accepted when asynchronous
     */
    WorkflowExecution start(StartExecutionCommand command);

    /**
     * @param executionId execution id
     * @return the execution
     * @throws com.orchpilot.workflow.exception.ExecutionNotFoundException when absent
     */
    WorkflowExecution get(String executionId);

    /**
     * @param workflowId optional workflow filter
     * @param status     optional status filter
     * @param pageable   paging
     * @return a page of executions, most recent first
     */
    Page<WorkflowExecution> list(String workflowId, ExecutionStatus status, Pageable pageable);

    /**
     * @param executionId execution id
     * @param limit       maximum entries
     * @return log entries in sequence order
     */
    List<ExecutionLogEntry> logs(String executionId, int limit);

    /**
     * Satisfies a parked node and continues the execution.
     *
     * @param executionId execution id
     * @param request     the submission
     * @param actor       who submitted it
     * @return the execution in its new state
     * @throws com.orchpilot.workflow.exception.InvalidWorkflowStateException when the execution is not waiting, or
     *                                                                    the submission is for a different node
     */
    WorkflowExecution submitSignal(String executionId, FormSubmissionRequest request, String actor);

    /**
     * Continues a paused execution, or re-enters one left running by a crashed instance.
     *
     * @param executionId execution id
     * @param async       whether to run on the engine's pool
     * @param actor       who resumed it
     * @return the execution in its new state
     */
    WorkflowExecution resume(String executionId, boolean async, String actor);

    /**
     * @param executionId execution id
     * @param actor       who paused it
     * @return the execution in its new state
     */
    WorkflowExecution pause(String executionId, String actor);

    /**
     * Cancels an execution, wherever in the cluster it is running.
     *
     * @param executionId execution id
     * @param actor       who cancelled it
     * @return the execution in its new state
     */
    WorkflowExecution cancel(String executionId, String actor);
}
