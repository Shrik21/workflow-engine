package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.exception.ExecutionNotFoundException;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The only writer of {@code workflow_executions}.
 *
 * <p>Funnelling every write through one component is what makes optimistic locking meaningful. The
 * execution document is written by the engine at each node boundary, by the heartbeat task, by the
 * cancel and pause endpoints, and by crash recovery; if any of those wrote directly through the
 * repository with a stale copy, a resumed execution could silently lose the variables another instance
 * had already committed.
 *
 * <p>Two different write styles, chosen deliberately:
 *
 * <ul>
 *   <li>{@link #save(WorkflowExecution)} goes through the mapping layer and therefore bumps the
 *       {@code @Version} field. A concurrent modification is reported rather than merged.</li>
 *   <li>{@link #heartbeat(String)} and {@link #claimForRecovery} use targeted {@code $set} updates that
 *       do not touch the version field, so an in-flight engine loop's copy stays valid. A heartbeat
 *       must never invalidate the document the engine is about to save.</li>
 * </ul>
 */
@Component
public class ExecutionStateStore {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateStore.class);

    private final WorkflowExecutionRepository repository;
    private final MongoTemplate mongoTemplate;

    public ExecutionStateStore(WorkflowExecutionRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * @param execution execution to persist
     * @return the saved execution with a refreshed document version
     * @throws org.springframework.dao.OptimisticLockingFailureException when another writer won
     */
    public WorkflowExecution save(WorkflowExecution execution) {
        execution.setUpdatedAt(Instant.now());
        return repository.save(execution);
    }

    /**
     * @param executionId execution id
     * @return the execution
     * @throws ExecutionNotFoundException when it does not exist
     */
    public WorkflowExecution require(String executionId) {
        return repository.findById(executionId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
    }

    /**
     * @param executionId execution id
     * @return the execution, or empty
     */
    public java.util.Optional<WorkflowExecution> find(String executionId) {
        return repository.findById(executionId);
    }

    /**
     * Refreshes liveness without disturbing the document version.
     *
     * @param executionId execution id
     */
    public void heartbeat(String executionId) {
        setFieldsWithoutTouchingVersion(executionId, new org.bson.Document("heartbeatAt", Instant.now()),
                "heartbeat");
    }

    /**
     * Publishes which node is executing right now, before it finishes.
     *
     * <p>The timeline is built from {@code nodeHistory}, which a node only joins once it returns. A node that
     * takes minutes — an AI agent reasoning through a tool loop, a cloud operation being polled — therefore shows
     * as nothing at all while the execution reads RUNNING, which reads to an operator as a hung engine. This
     * marker is what lets a console say "AI Agent, running for 40s" instead.
     *
     * <p>Best-effort — losing the marker costs visibility, never correctness.
     *
     * @param executionId execution id
     * @param nodeId      node about to execute
     * @param nodeType    its type, for display
     * @param startedAt   when the attempt began
     */
    public void markNodeInFlight(String executionId, String nodeId, String nodeType, Instant startedAt) {
        org.bson.Document fields = new org.bson.Document()
                .append("currentNodeId", nodeId)
                .append("currentNodeType", nodeType)
                .append("currentNodeStartedAt", startedAt);
        setFieldsWithoutTouchingVersion(executionId, fields, "in-flight marker for node " + nodeId);
    }

    /**
     * Applies a progress-only {@code $set} that must never disturb the document's {@code @Version}.
     *
     * <h2>Why this bypasses {@code MongoTemplate}'s entity-aware update</h2>
     *
     * {@code mongoTemplate.updateFirst(query, update, WorkflowExecution.class)} looks like a plain targeted
     * write, but Spring Data inspects the entity and silently appends {@code $inc: {version: 1}} to any update
     * on a {@code @Version} class that does not already modify that field
     * ({@code QueryOperations.increaseVersionForUpdateIfNecessary}). That is correct for a real domain update
     * and catastrophic here: the engine loop is holding the document at version N and is about to
     * {@code save()} it, so bumping the stored version to N+1 underneath it makes that save fail with
     * {@code OptimisticLockingFailureException}, the loop stands down, and the execution is left stuck in
     * {@code RUNNING} with its next node never run.
     *
     * <p>Writing through the raw collection skips entity mapping entirely, so the version is untouched and the
     * loop's pending save still matches. Progress markers are metadata about a run, not changes to it, and must
     * never be able to interfere with the run itself.
     *
     * @param executionId execution to update
     * @param fields      fields to set
     * @param what        short description used only for the failure log
     */
    private void setFieldsWithoutTouchingVersion(String executionId, org.bson.Document fields, String what) {
        try {
            mongoTemplate.getCollection(mongoTemplate.getCollectionName(WorkflowExecution.class))
                    .updateOne(new org.bson.Document("_id", executionId), new org.bson.Document("$set", fields));
        } catch (RuntimeException ex) {
            log.warn("Could not write {} for execution {}: {}", what, executionId, ex.getMessage());
        }
    }

    /**
     * Reads only the current status, for cheap cancellation and pause checks at node boundaries.
     *
     * @param executionId execution id
     * @return the persisted status, or empty when the execution is gone
     */
    public java.util.Optional<ExecutionStatus> currentStatus(String executionId) {
        Query query = Query.query(Criteria.where("_id").is(executionId));
        query.fields().include("status");
        WorkflowExecution projection = mongoTemplate.findOne(query, WorkflowExecution.class);
        return projection == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(projection.getStatus());
    }

    /**
     * Atomically moves an instance from one of a set of expected statuses to a target status, applying any
     * extra field updates in the same write.
     *
     * <p>This is the primitive the pause / resume / terminate lifecycle is built on. The status precondition
     * lives <em>inside</em> the update, so two administrators — or an administrator and the engine loop — racing
     * to change the same instance cannot both succeed: exactly one write matches, the other matches nothing.
     * <p>Note that this <em>does</em> advance the document's {@code @Version}: Spring Data appends an
     * {@code $inc} on the version to any entity-aware update that does not already modify it. That is
     * appropriate here — a status transition is a real change to the instance, not a progress marker — but it
     * does mean an engine loop holding an older copy will lose its next {@code save()} and stand down. For a
     * pause or a terminate that is the intended end state anyway. Contrast
     * {@link #setFieldsWithoutTouchingVersion}, which exists precisely because progress markers must not have
     * that effect.
     *
     * @param executionId the instance
     * @param expected    the statuses the transition is legal from; the write matches only these
     * @param target      the status to move to
     * @param extra       additional field updates to apply atomically, e.g. the pause reason; may be null
     * @return {@code true} when this call performed the transition, {@code false} when the instance was not in
     *         any expected status (already moved, already terminal, or gone)
     */
    public boolean transitionStatus(String executionId, java.util.Set<ExecutionStatus> expected,
                                    ExecutionStatus target, java.util.function.Consumer<Update> extra) {
        Criteria criteria = Criteria.where("_id").is(executionId).and("status").in(expected);
        Update update = new Update().set("status", target).set("updatedAt", Instant.now());
        if (extra != null) {
            extra.accept(update);
        }
        long modified = mongoTemplate.updateFirst(Query.query(criteria), update, WorkflowExecution.class)
                .getModifiedCount();
        return modified > 0;
    }

    /**
     * Takes ownership of an execution abandoned by another instance.
     *
     * <p>The guard on {@code ownerInstance} and {@code heartbeatAt} is inside the update, not around it:
     * two instances sweeping at the same moment must not both conclude they may resume the same run, and
     * only a conditional write can guarantee that.
     *
     * @param executionId    execution to claim
     * @param newOwner       this instance's id
     * @param previousOwner  owner recorded on the document, used as part of the guard
     * @param staleBefore    heartbeat must be older than this
     * @return {@code true} when this instance now owns the execution
     */
    public boolean claimForRecovery(String executionId, String newOwner, String previousOwner,
                                    Instant staleBefore) {
        Criteria criteria = Criteria.where("_id").is(executionId)
                .and("status").is(ExecutionStatus.RUNNING)
                .and("ownerInstance").is(previousOwner)
                .and("heartbeatAt").lt(staleBefore);
        Update update = new Update()
                .set("ownerInstance", newOwner)
                .set("heartbeatAt", Instant.now())
                .set("updatedAt", Instant.now());
        long modified = mongoTemplate.updateFirst(Query.query(criteria), update, WorkflowExecution.class)
                .getModifiedCount();
        if (modified > 0) {
            log.info("Claimed abandoned execution {} from instance {}", executionId, previousOwner);
            return true;
        }
        return false;
    }
}
