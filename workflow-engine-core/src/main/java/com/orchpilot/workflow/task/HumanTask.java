package com.orchpilot.workflow.task;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A unit of work waiting for a person.
 *
 * <p>This document is what turns a parked execution into something a human can find. Before it existed a form
 * node parked the execution and the only way to discover it was to list every {@code WAITING} execution, which
 * is an operator's view of the world rather than a participant's: it cannot express "mine", it cannot express
 * "unclaimed", and it exposes every run to everyone who can read executions.
 *
 * <h2>Why a separate collection</h2>
 *
 * <p>The task could have lived inside the execution's pending signal. It does not, for three reasons that all
 * come down to querying: an inbox filters by assignee, by candidate group and by due date, and none of those
 * are indexable inside a nested object on a document keyed by execution. A task also outlives the wait — a
 * completed task is the audit record of who approved what — and the execution's pending signal is cleared the
 * moment it resumes.
 *
 * <h2>Idempotency</h2>
 *
 * <p>The unique index on {@code (workflowExecutionId, nodeId, attempt)} is what makes task creation safe to
 * repeat. A node can be re-entered: a retry, a resume after a crash, an engine on another instance picking the
 * execution up. Each of those calls the executor again, and without the index each call would raise another
 * copy of the same approval. {@code attempt} is part of the key rather than absent so that a genuinely new
 * wait at the same node — a loop that comes back round — gets its own task instead of colliding with the
 * completed one.
 */
@Document(collection = "human_tasks")
@CompoundIndex(name = "uk_task_execution_node",
        def = "{'workflowExecutionId': 1, 'nodeId': 1, 'attempt': 1}", unique = true)
@CompoundIndex(name = "ix_task_assignee_status", def = "{'assigneeUserId': 1, 'status': 1}")
@CompoundIndex(name = "ix_task_candidates_status", def = "{'candidateGroupIds': 1, 'status': 1}")
public class HumanTask {

    @Id
    private String id;

    // ------------------------------------------------------------------ provenance

    @Indexed
    private String workflowExecutionId;

    private String workflowId;
    private String workflowName;
    private int workflowVersion;

    /** Node that raised the task. With the execution id, this identifies the wait. */
    private String nodeId;

    private String nodeName;

    /**
     * Which visit to this node raised the task.
     *
     * <p>1 for the ordinary case. Incremented when a loop returns to a node whose earlier task is already
     * finished, so the unique index does not refuse the second, legitimate wait.
     */
    private int attempt = 1;

    // ----------------------------------------------------------------------- form

    private String formDefinitionId;

    /**
     * The exact form version this task renders.
     *
     * <p>Pinned at creation. A task raised against version 3 renders version 3 for as long as it lives,
     * whatever the designer does to the draft afterwards; otherwise the labels somebody read when they
     * received the task would not be the labels they submitted against.
     */
    private int formVersion;

    /** Prefilled values, resolved from the execution's variables when the task was created. */
    private Map<String, Object> prefill = new LinkedHashMap<>();

    // ------------------------------------------------------------------ presentation

    private String taskName;
    private String description;

    private TaskStatus status = TaskStatus.OPEN;

    /**
     * Whether this task is completed by an external customer through a secure form link rather than by an
     * internal user. An external task has no internal assignee; it appears in the internal task bucket as
     * "waiting for external response" and is completed by the public form submit path.
     */
    private boolean external;

    /**
     * The status this task held before its instance paused it, so resume can restore it exactly.
     *
     * <p>Only meaningful while {@link #status} is {@link TaskStatus#PAUSED}: an {@code ASSIGNED} task that is
     * paused records {@code ASSIGNED} here, and resuming returns it there rather than guessing. Null at every
     * other time.
     */
    private TaskStatus previousStatus;

    private TaskPriority priority = TaskPriority.NORMAL;

    /**
     * {@link TaskPriority#weight()}, stored so MongoDB can sort by urgency.
     *
     * <p>Denormalised because the enum is persisted as its name, and sorting on a name orders tasks
     * alphabetically: descending by {@code priority} puts URGENT first and then NORMAL, LOW, HIGH. A derived
     * numeric field is the cheapest way to make the database's sort mean what the reader expects. Kept in step by
     * {@link #setPriority}, which is the only way to change it.
     */
    private int priorityWeight = TaskPriority.NORMAL.weight();

    // ------------------------------------------------------------------- assignment

    /** Who is accountable. Null while the task is OPEN. */
    @Indexed
    private String assigneeUserId;

    /** Denormalised for display, so an inbox does not need a user lookup per row. */
    private String assigneeUsername;

    /** Users who may claim it. Empty means "anyone in a candidate group". */
    private List<String> candidateUserIds = new ArrayList<>();

    /** Groups whose members may claim it. */
    private List<String> candidateGroupIds = new ArrayList<>();

    // ---------------------------------------------------------------------- timing

    private Instant createdAt;
    private Instant updatedAt;

    /** Advisory deadline. Passing it makes the task overdue, not invalid. */
    private Instant dueAt;

    /**
     * Hard deadline. Passing it expires the task and fails the execution.
     *
     * <p>Separate from {@code dueAt} on purpose. "This was due yesterday" and "this can no longer be done"
     * are different statements, and conflating them means either nagging cannot be configured without also
     * introducing a failure mode, or a deadline cannot be enforced without also nagging.
     */
    private Instant expiresAt;

    private Instant claimedAt;
    private Instant completedAt;

    // --------------------------------------------------------------------- outcome

    private String completedByUserId;
    private String completedByUsername;

    /**
     * What was submitted, keyed by field name.
     *
     * <p>Kept so the history is readable: "approved with comment X" is the answer to most questions asked of
     * a completed task, and reconstructing it from the execution's variables afterwards is guesswork once a
     * later node has overwritten them.
     */
    private Map<String, Object> submittedData = new LinkedHashMap<>();

    /** Partial input the assignee saved without submitting. */
    private Map<String, Object> draftData = new LinkedHashMap<>();

    private Instant draftSavedAt;

    private String cancelReason;

    /** Who raised it: the username that started the execution, or {@code system} for a scheduled run. */
    private String createdBy;

    private String correlationId;

    /**
     * Optimistic locking, so two people cannot claim the same open task.
     *
     * <p>The alternative is a findAndModify with a status precondition, which is equally correct and less
     * readable. This way the service reads, checks, and saves, and the loser of the race gets a 409 from the
     * existing handler rather than silently overwriting the winner.
     */
    @Version
    private Long documentVersion;

    // -------------------------------------------------------------------- behaviour

    /** @return whether the task is past its advisory deadline */
    public boolean isOverdue() {
        return dueAt != null && status.isActionable() && dueAt.isBefore(Instant.now());
    }

    /** @return whether this person is the assignee */
    public boolean isAssignedTo(String userId) {
        return userId != null && userId.equals(assigneeUserId);
    }

    /**
     * Whether this person is offered the task without being its assignee.
     *
     * @param userId   the user
     * @param groupIds the groups the user belongs to
     * @return whether they are a candidate
     */
    public boolean isCandidate(String userId, java.util.Collection<String> groupIds) {
        if (userId != null && candidateUserIds.contains(userId)) {
            return true;
        }
        if (groupIds == null || candidateGroupIds.isEmpty()) {
            return false;
        }
        for (String groupId : candidateGroupIds) {
            if (groupIds.contains(groupId)) {
                return true;
            }
        }
        return false;
    }

    /** @return whether the task names any candidate at all */
    public boolean hasCandidates() {
        return !candidateUserIds.isEmpty() || !candidateGroupIds.isEmpty();
    }

    // ------------------------------------------------------------------ accessors

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public int getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(int workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = Math.max(1, attempt);
    }

    public String getFormDefinitionId() {
        return formDefinitionId;
    }

    public void setFormDefinitionId(String formDefinitionId) {
        this.formDefinitionId = formDefinitionId;
    }

    public int getFormVersion() {
        return formVersion;
    }

    public void setFormVersion(int formVersion) {
        this.formVersion = formVersion;
    }

    public Map<String, Object> getPrefill() {
        return prefill;
    }

    public void setPrefill(Map<String, Object> prefill) {
        this.prefill = prefill == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prefill);
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    public TaskStatus getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(TaskStatus previousStatus) {
        this.previousStatus = previousStatus;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status == null ? TaskStatus.OPEN : status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority == null ? TaskPriority.NORMAL : priority;
        this.priorityWeight = this.priority.weight();
    }

    public int getPriorityWeight() {
        return priorityWeight;
    }

    /** Present for the document mapper. Prefer {@link #setPriority}, which keeps the two in step. */
    public void setPriorityWeight(int priorityWeight) {
        this.priorityWeight = priorityWeight;
    }

    public String getAssigneeUserId() {
        return assigneeUserId;
    }

    public void setAssigneeUserId(String assigneeUserId) {
        this.assigneeUserId = assigneeUserId;
    }

    public String getAssigneeUsername() {
        return assigneeUsername;
    }

    public void setAssigneeUsername(String assigneeUsername) {
        this.assigneeUsername = assigneeUsername;
    }

    public List<String> getCandidateUserIds() {
        return candidateUserIds;
    }

    public void setCandidateUserIds(List<String> candidateUserIds) {
        this.candidateUserIds = candidateUserIds == null ? new ArrayList<>() : new ArrayList<>(candidateUserIds);
    }

    public List<String> getCandidateGroupIds() {
        return candidateGroupIds;
    }

    public void setCandidateGroupIds(List<String> candidateGroupIds) {
        this.candidateGroupIds = candidateGroupIds == null
                ? new ArrayList<>() : new ArrayList<>(candidateGroupIds);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getCompletedByUserId() {
        return completedByUserId;
    }

    public void setCompletedByUserId(String completedByUserId) {
        this.completedByUserId = completedByUserId;
    }

    public String getCompletedByUsername() {
        return completedByUsername;
    }

    public void setCompletedByUsername(String completedByUsername) {
        this.completedByUsername = completedByUsername;
    }

    public Map<String, Object> getSubmittedData() {
        return submittedData;
    }

    public void setSubmittedData(Map<String, Object> submittedData) {
        this.submittedData = submittedData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(submittedData);
    }

    public Map<String, Object> getDraftData() {
        return draftData;
    }

    public void setDraftData(Map<String, Object> draftData) {
        this.draftData = draftData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(draftData);
    }

    public Instant getDraftSavedAt() {
        return draftSavedAt;
    }

    public void setDraftSavedAt(Instant draftSavedAt) {
        this.draftSavedAt = draftSavedAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    @Override
    public String toString() {
        return "HumanTask{" + id + " " + status + " on " + workflowExecutionId + "/" + nodeId + "}";
    }
}
