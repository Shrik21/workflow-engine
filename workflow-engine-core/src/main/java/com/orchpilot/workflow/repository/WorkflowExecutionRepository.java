package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Access to execution state.
 */
@Repository
public interface WorkflowExecutionRepository extends MongoRepository<WorkflowExecution, String> {

    Page<WorkflowExecution> findByWorkflowIdOrderByStartedAtDesc(String workflowId, Pageable pageable);

    Page<WorkflowExecution> findByStatusOrderByStartedAtDesc(ExecutionStatus status, Pageable pageable);

    /**
     * Filtered listings whose order comes from the {@link Pageable}'s sort rather than the method name.
     *
     * <p>The {@code OrderByStartedAtDesc} variants above bake the order in; these leave it to the caller, so
     * the list endpoint can order every path — filtered by workflow, filtered by status, or unfiltered — by
     * the same {@code updatedAt} descending, putting the most recently changed execution at the top.
     *
     * @param workflowId the workflow, for the workflow-filtered listing
     * @param pageable   page and sort
     * @return the page
     */
    Page<WorkflowExecution> findByWorkflowId(String workflowId, Pageable pageable);

    Page<WorkflowExecution> findByStatus(ExecutionStatus status, Pageable pageable);

    /**
     * Filtered by both a workflow and a status.
     *
     * <p>The combination the list endpoint needs when an operator, already looking at one workflow's runs,
     * clicks a status filter. Without it the workflow-only query wins and the status is silently ignored.
     *
     * @param workflowId the workflow
     * @param status     the status
     * @param pageable   page and sort
     * @return the page
     */
    Page<WorkflowExecution> findByWorkflowIdAndStatus(String workflowId, ExecutionStatus status,
                                                      Pageable pageable);

    Optional<WorkflowExecution> findByIdempotencyKey(String idempotencyKey);

    /**
     * Candidates for crash recovery: still marked running but not heartbeating.
     *
     * @param status  status to look for
     * @param cutoff  heartbeats older than this are considered abandoned
     * @param pageable batch size
     * @return abandoned executions
     */
    List<WorkflowExecution> findByStatusAndHeartbeatAtBefore(ExecutionStatus status, Instant cutoff, Pageable pageable);

    List<WorkflowExecution> findByStatusInAndWorkflowId(List<ExecutionStatus> statuses, String workflowId);

    long countByWorkflowIdAndStatus(String workflowId, ExecutionStatus status);
}
