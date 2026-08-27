package com.orchpilot.workflow.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Task queries, one per inbox bucket.
 *
 * <p>Mostly derived queries, because each corresponds to a bucket somebody clicks on and the method name says
 * which. The claimable query is written out as JSON instead: it is a disjunction over two different array
 * fields, and the derived-name form of that
 * ({@code findByStatusAndCandidateGroupIdsInOrStatusAndCandidateUserIdsContaining}) is both unreadable and
 * fragile about how the parser groups the conditions. An explicit criteria document leaves nothing to infer.
 *
 * <p>A top-level interface, like every other repository here. Spring Data does not create proxies for
 * interfaces nested inside a container class, and grouping them to save a file produces a context that fails
 * to start.
 */
public interface HumanTaskRepository extends MongoRepository<HumanTask, String> {

    /** The idempotency lookup: has this node already raised a task on this execution? */
    List<HumanTask> findByWorkflowExecutionIdAndNodeIdOrderByAttemptDesc(String executionId, String nodeId);

    List<HumanTask> findByWorkflowExecutionId(String executionId);

    List<HumanTask> findByWorkflowExecutionIdAndStatusIn(String executionId, Collection<TaskStatus> statuses);

    // ------------------------------------------------------------------- my tasks

    Page<HumanTask> findByAssigneeUserIdAndStatusIn(String userId, Collection<TaskStatus> statuses,
                                                   Pageable pageable);

    long countByAssigneeUserIdAndStatusIn(String userId, Collection<TaskStatus> statuses);

    // ----------------------------------------------------------- available to claim

    /**
     * Open tasks this person could claim.
     *
     * <p>Matches either candidate list, so a task offered to a named user and a task offered to a group both
     * appear. An OPEN task naming no candidate at all matches nothing here and is visible only to an
     * administrator: an unaddressed task is a workflow design mistake, and quietly offering it to everybody
     * hides that instead of surfacing it.
     */
    @Query("{ 'status': ?0, $or: [ { 'candidateGroupIds': { $in: ?1 } }, { 'candidateUserIds': ?2 } ] }")
    Page<HumanTask> findClaimable(TaskStatus status, Collection<String> groupIds, String userId,
                                  Pageable pageable);

    @Query(value = "{ 'status': ?0, $or: [ { 'candidateGroupIds': { $in: ?1 } }, "
            + "{ 'candidateUserIds': ?2 } ] }", count = true)
    long countClaimable(TaskStatus status, Collection<String> groupIds, String userId);

    // ------------------------------------------------------------------ everything

    Page<HumanTask> findByStatusIn(Collection<TaskStatus> statuses, Pageable pageable);

    Page<HumanTask> findByWorkflowIdAndStatusIn(String workflowId, Collection<TaskStatus> statuses,
                                                Pageable pageable);

    long countByStatusIn(Collection<TaskStatus> statuses);

    // ------------------------------------------------------------------- scheduler

    /** Actionable tasks whose hard deadline has passed. */
    List<HumanTask> findByStatusInAndExpiresAtBefore(Collection<TaskStatus> statuses, Instant cutoff);

    /** Actionable tasks past their advisory deadline, for reminders. */
    List<HumanTask> findByStatusInAndDueAtBefore(Collection<TaskStatus> statuses, Instant cutoff);
}
