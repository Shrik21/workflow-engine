package com.orchpilot.workflow.task;

import com.orchpilot.workflow.access.GroupMembership;
import com.orchpilot.workflow.access.GroupMembershipRepository;
import com.orchpilot.workflow.auth.model.Permission;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides what somebody may do to a task.
 *
 * <h2>Why this is not the group authorization service</h2>
 *
 * <p>Workflow authorization answers "may this account touch that workflow". Task authorization answers a
 * narrower and mostly unrelated question: "is this task theirs". A user with {@code WORKFLOW_EXECUTE} on a
 * workflow may start a hundred runs and is entitled to see none of the approval tasks those runs raise for
 * somebody else, because a task carries what a person typed into a form and that is theirs, not the workflow's.
 *
 * <p>So the rules here are about assignment, not about the workflow:
 *
 * <ul>
 *   <li><b>View</b>: the assignee, a candidate, or somebody holding {@code TASK_VIEW_ALL}. Nobody else, not even
 *       the person who started the execution.</li>
 *   <li><b>Claim</b>: a candidate, while the task is open.</li>
 *   <li><b>Complete</b>: the assignee, and only the assignee.</li>
 *   <li><b>Reassign</b>: an administrator, or the current assignee delegating their own work.</li>
 *   <li><b>Cancel</b>: an administrator.</li>
 * </ul>
 *
 * <h2>An administrator cannot complete somebody else's task</h2>
 *
 * <p>{@code TASK_ADMIN} grants cancel and reassign but deliberately not complete. Submitting a form on another
 * person's behalf writes their name against an approval they never gave, which makes the record of who approved
 * what worthless — and that record is the only reason to build human tasks rather than a REST call. An
 * administrator who genuinely must finish somebody's task reassigns it to themselves first, which leaves a
 * REASSIGNED entry in the history saying exactly that happened.
 *
 * <h2>Membership is read per check</h2>
 *
 * <p>Not cached and not in the JWT, for the same reason group permissions are not: removing somebody from a
 * group must take their tasks away now, not when a token expires.
 */
@Service("taskAuthorizationService")
public class TaskAuthorizationService {

    private final GroupMembershipRepository memberships;

    public TaskAuthorizationService(GroupMembershipRepository memberships) {
        this.memberships = memberships;
    }

    /**
     * @param principal the caller
     * @param task      the task
     * @return whether they may read it, including its prefilled and submitted values
     */
    public boolean canView(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null) {
            return false;
        }
        if (principal.has(Permission.TASK_VIEW_ALL) || principal.has(Permission.TASK_ADMIN)) {
            return true;
        }
        return isMine(principal, task) || isCandidate(principal, task);
    }

    /**
     * @return whether they may take an unclaimed task
     */
    public boolean canClaim(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null || task.getStatus() != TaskStatus.OPEN) {
            return false;
        }
        if (principal.has(Permission.TASK_ADMIN)) {
            return true;
        }
        return principal.has(Permission.TASK_CLAIM) && isCandidate(principal, task);
    }

    /**
     * @return whether they may give an assigned task back to the pool
     */
    public boolean canRelease(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null || task.getStatus() != TaskStatus.ASSIGNED) {
            return false;
        }
        // Releasing a task nobody else can pick up would strand it, so it takes candidates or an administrator.
        if (principal.has(Permission.TASK_ADMIN)) {
            return true;
        }
        return isMine(principal, task) && task.hasCandidates();
    }

    /**
     * @return whether they may submit it
     */
    public boolean canComplete(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null || !task.getStatus().isActionable()) {
            return false;
        }
        return principal.has(Permission.TASK_COMPLETE) && isMine(principal, task);
    }

    /**
     * Saving a draft takes the same <em>identity</em> authority as completing — assignee only, with
     * {@code TASK_COMPLETE} — but a broader <em>status</em> range.
     *
     * <p>Not a weaker check on who: a draft is stored on the task and read back by whoever holds it next, so
     * anybody who could write one could plant values in somebody else's form, which is why it stays
     * assignee-only. It is a broader check on when: a draft may be saved while the owning instance is paused or
     * terminated — statuses that are not {@code isActionable()} but do {@code allowsDraft()} — so a person never
     * loses form input to an administrative action they did not cause. Submitting, by contrast, still requires an
     * actionable task and a running instance; the two authorities diverge here on purpose.
     */
    public boolean canSaveDraft(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null || !task.getStatus().allowsDraft()) {
            return false;
        }
        return principal.has(Permission.TASK_COMPLETE) && isMine(principal, task);
    }

    /**
     * @return whether they may move it to somebody else
     */
    public boolean canReassign(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null || !task.getStatus().isActionable()) {
            return false;
        }
        if (principal.has(Permission.TASK_ADMIN)) {
            return true;
        }
        // Delegation: handing on work that is currently yours. Not a way to interfere with anybody else's.
        return principal.has(Permission.TASK_REASSIGN) && isMine(principal, task);
    }

    /**
     * @return whether they may withdraw it, which fails the step that raised it
     */
    public boolean canCancel(AuthPrincipal principal, HumanTask task) {
        if (principal == null || task == null || !task.getStatus().isActionable()) {
            return false;
        }
        return principal.has(Permission.TASK_ADMIN)
                || (principal.has(Permission.TASK_CANCEL) && principal.has(Permission.EXECUTION_CANCEL));
    }

    /**
     * @return whether they may list every task rather than only their own
     */
    public boolean canViewAll(AuthPrincipal principal) {
        return principal != null
                && (principal.has(Permission.TASK_VIEW_ALL) || principal.has(Permission.TASK_ADMIN));
    }

    /**
     * The groups a user belongs to.
     *
     * <p>Public because the query layer needs the same set to build the "available to me" bucket, and deriving
     * it twice in two places is how the list and the per-task check come to disagree.
     *
     * @param userId the user
     * @return their group ids, possibly empty
     */
    public Set<String> groupsOf(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        if (userId == null) {
            return ids;
        }
        for (GroupMembership membership : memberships.findByUserId(userId)) {
            ids.add(membership.getGroupId());
        }
        return ids;
    }

    private boolean isMine(AuthPrincipal principal, HumanTask task) {
        return task.isAssignedTo(principal.getUserId());
    }

    private boolean isCandidate(AuthPrincipal principal, HumanTask task) {
        return task.isCandidate(principal.getUserId(), groupsOf(principal.getUserId()));
    }
}
