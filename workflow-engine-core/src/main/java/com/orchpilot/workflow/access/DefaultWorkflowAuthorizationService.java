package com.orchpilot.workflow.access;

import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.repository.WorkflowExecutionRepository;
import com.orchpilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Group-based authorization for workflows.
 *
 * <h2>Why nothing is cached</h2>
 *
 * Permissions are read from MongoDB on every check, and none of them are in the JWT. An administrator who
 * removes a group's execute permission expects that to take effect now, not when a token expires fifteen
 * minutes later, and a user removed from a group should lose access immediately. Both of those are the
 * situations where authorization matters most.
 *
 * <p>The cost is two indexed lookups per check: the user's memberships by {@code userId}, and the groups by
 * id. Both are covered by indexes and both are small. If profiling ever showed this to be a bottleneck, the
 * fix is a cache with an explicit invalidation hook on every group, membership and workflow-access mutation,
 * not moving permissions into the token where they cannot be revoked at all.
 */
@Service("workflowAuthorizationService")
public class DefaultWorkflowAuthorizationService implements WorkflowAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowAuthorizationService.class);

    private final WorkflowRepository workflows;
    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final UserRepository users;

    /** Resolves an execution to its workflow, so an execution is exactly as private as its workflow. */
    private final WorkflowExecutionRepository executions;

    public DefaultWorkflowAuthorizationService(WorkflowRepository workflows, GroupRepository groups,
                                               GroupMembershipRepository memberships, UserRepository users,
                                               WorkflowExecutionRepository executions) {
        this.workflows = workflows;
        this.groups = groups;
        this.memberships = memberships;
        this.users = users;
        this.executions = executions;
    }

    @Override
    public boolean hasPermission(String userId, String workflowId, WorkflowPermission permission) {
        if (userId == null || workflowId == null || permission == null) {
            return false;
        }

        // Administrators are allowed everything without belonging to any group. Checked first because it is
        // one lookup and short-circuits the rest.
        if (isAdmin(userId)) {
            return true;
        }

        Optional<Workflow> found = workflows.findById(workflowId);
        if (found.isEmpty()) {
            // Deny rather than 404 here. Whether a workflow exists is itself information, and the caller
            // gets the same answer either way; the controller decides how to phrase it.
            return false;
        }
        Workflow workflow = found.get();

        /*
         * Ownership and group grants are unioned, not alternatives.
         *
         * Returning early for the owner looks equivalent and is not: an owner who also belongs to a group
         * attached to their own workflow would lose whatever that group adds beyond the owner defaults,
         * such as the operational permissions ownership deliberately withholds. Worse, it would disagree
         * with effectivePermissions, which does union them, so the console would show a button that this
         * method then refuses.
         */
        if (userId.equals(workflow.getOwnerId())
                && WorkflowPermission.ownerDefaults().contains(permission)) {
            return true;
        }

        return effectiveGroupPermissions(userId, workflow).contains(permission);
    }

    @Override
    public Set<WorkflowPermission> effectivePermissions(String userId, String workflowId) {
        if (userId == null || workflowId == null) {
            return Set.of();
        }
        if (isAdmin(userId)) {
            return EnumSet.allOf(WorkflowPermission.class);
        }

        Optional<Workflow> found = workflows.findById(workflowId);
        if (found.isEmpty()) {
            return Set.of();
        }
        Workflow workflow = found.get();

        Set<WorkflowPermission> effective = EnumSet.noneOf(WorkflowPermission.class);
        if (userId.equals(workflow.getOwnerId())) {
            effective.addAll(WorkflowPermission.ownerDefaults());
        }
        // Union, not replacement: someone can own a workflow and also be in a group that grants more, such
        // as the operational permissions the owner defaults deliberately withhold.
        effective.addAll(effectiveGroupPermissions(userId, workflow));
        return effective;
    }

    @Override
    public AccessScope visibleWorkflowScope(String userId) {
        if (userId == null) {
            return new AccessScope(false, null, Set.of());
        }
        if (isAdmin(userId)) {
            return new AccessScope(true, null, Set.of());
        }
        // Only groups that actually grant VIEW are included, so the list matches what the user could open.
        Set<String> viewable = new LinkedHashSet<>();
        for (Group group : enabledGroupsOf(userId)) {
            if (group.grants(WorkflowPermission.WORKFLOW_VIEW)) {
                viewable.add(group.getId());
            }
        }
        return new AccessScope(false, userId, viewable);
    }

    @Override
    public boolean canView(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.WORKFLOW_VIEW);
    }

    @Override
    public boolean canEdit(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.WORKFLOW_EDIT);
    }

    @Override
    public boolean canExecute(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.WORKFLOW_EXECUTE);
    }

    @Override
    public boolean canDelete(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.WORKFLOW_DELETE);
    }

    @Override
    public boolean canPublish(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.WORKFLOW_PUBLISH);
    }

    @Override
    public boolean canClone(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.WORKFLOW_CLONE);
    }

    @Override
    public boolean canViewExecution(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.EXECUTION_VIEW);
    }

    @Override
    public boolean canCancelExecution(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.EXECUTION_CANCEL);
    }

    @Override
    public boolean canRetryExecution(String userId, String workflowId) {
        return hasPermission(userId, workflowId, WorkflowPermission.EXECUTION_RETRY);
    }

    @Override
    public boolean canManageAccess(String userId, String workflowId) {
        if (userId == null || workflowId == null) {
            return false;
        }
        if (isAdmin(userId)) {
            return true;
        }
        // Ownership, not WORKFLOW_EDIT. A user granted edit through a group must not be able to attach
        // further groups: that would let them widen their own access without an administrator.
        return workflows.findById(workflowId)
                .map(workflow -> userId.equals(workflow.getOwnerId()))
                .orElse(false);
    }

    // -------------------------------------------------------- SpEL-friendly overloads

    /**
     * Overloads taking the {@link Authentication} directly, so an annotation can read
     * {@code @workflowAuthorizationService.canExecute(authentication, #id)} without first digging the user id
     * out of the principal in the expression, which is error-prone and unreadable.
     *
     * <p>{@code authentication.name} is the <em>username</em>, not the id, so an expression using it would
     * silently never match an owner. These overloads take the principal and read the id correctly.
     */
    public boolean canView(Authentication authentication, String workflowId) {
        return canView(userId(authentication), workflowId);
    }

    public boolean canEdit(Authentication authentication, String workflowId) {
        return canEdit(userId(authentication), workflowId);
    }

    public boolean canExecute(Authentication authentication, String workflowId) {
        return canExecute(userId(authentication), workflowId);
    }

    public boolean canDelete(Authentication authentication, String workflowId) {
        return canDelete(userId(authentication), workflowId);
    }

    public boolean canPublish(Authentication authentication, String workflowId) {
        return canPublish(userId(authentication), workflowId);
    }

    public boolean canClone(Authentication authentication, String workflowId) {
        return canClone(userId(authentication), workflowId);
    }

    public boolean canManageAccess(Authentication authentication, String workflowId) {
        return canManageAccess(userId(authentication), workflowId);
    }

    // ------------------------------------------------- execution-scoped checks

    /**
     * Authorization for an endpoint addressed by execution id rather than workflow id.
     *
     * <p>These exist because knowing an execution id must not be enough to read it. The id is resolved to
     * its workflow and the permission is evaluated there, so an execution is exactly as private as the
     * workflow that produced it.
     *
     * <p>A missing execution is denied rather than reported as absent. Answering 403 for "not yours" and 404
     * for "does not exist" would let anyone probe which ids are real.
     *
     * @param authentication the caller
     * @param executionId    the execution
     * @return whether the caller may see it
     */
    public boolean canViewExecutionById(Authentication authentication, String executionId) {
        return onExecution(authentication, executionId, WorkflowPermission.EXECUTION_VIEW);
    }

    /** Cancelling, pausing and resuming are the same capability: interfering with a running execution. */
    public boolean canCancelExecutionById(Authentication authentication, String executionId) {
        return onExecution(authentication, executionId, WorkflowPermission.EXECUTION_CANCEL);
    }

    public boolean canRetryExecutionById(Authentication authentication, String executionId) {
        return onExecution(authentication, executionId, WorkflowPermission.EXECUTION_RETRY);
    }

    /**
     * Submitting a form is participating in a workflow rather than administering it, so it takes
     * {@code WORKFLOW_EXECUTE}: whoever may start a workflow may answer the tasks it raises.
     */
    public boolean canSubmitFormById(Authentication authentication, String executionId) {
        return onExecution(authentication, executionId, WorkflowPermission.WORKFLOW_EXECUTE);
    }

    private boolean onExecution(Authentication authentication, String executionId,
                                WorkflowPermission permission) {
        String userId = userId(authentication);
        if (userId == null || executionId == null) {
            return false;
        }
        return executions.findById(executionId)
                .map(execution -> hasPermission(userId, execution.getWorkflowId(), permission))
                .orElse(false);
    }

    /** @return the authenticated user's id, or null when the principal is not one of ours */
    public static String userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof AuthPrincipal principal ? principal.getUserId() : null;
    }

    // ------------------------------------------------------------------- internals

    /**
     * The union of permissions from groups the user belongs to that are also attached to the workflow.
     *
     * <p>This is the intersection-then-union the specification describes: intersect the two group sets, then
     * union the permissions of what remains. Any single matching group granting the permission is enough.
     */
    private Set<WorkflowPermission> effectiveGroupPermissions(String userId, Workflow workflow) {
        List<String> attached = workflow.getAccessGroups();
        if (attached == null || attached.isEmpty()) {
            // No groups attached: reachable only by the owner and administrators, both handled above.
            return Set.of();
        }

        Set<String> userGroupIds = membershipIds(userId);
        if (userGroupIds.isEmpty()) {
            return Set.of();
        }

        Set<String> matching = new LinkedHashSet<>(attached);
        matching.retainAll(userGroupIds);
        if (matching.isEmpty()) {
            return Set.of();
        }

        Set<WorkflowPermission> effective = EnumSet.noneOf(WorkflowPermission.class);
        // findByIdInAndEnabledTrue does the disabled-group filtering, so a disabled group grants nothing
        // even while its memberships and attachments remain intact.
        for (Group group : groups.findByIdInAndEnabledTrue(matching)) {
            effective.addAll(group.getPermissions());
        }
        return effective;
    }

    private Set<String> membershipIds(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        for (GroupMembership membership : memberships.findByUserId(userId)) {
            ids.add(membership.getGroupId());
        }
        return ids;
    }

    /** The user's enabled groups, resolved in one query rather than one per membership. */
    private List<Group> enabledGroupsOf(String userId) {
        Set<String> ids = membershipIds(userId);
        return ids.isEmpty() ? List.of() : groups.findByIdInAndEnabledTrue(ids);
    }

    /**
     * Whether the user is an administrator.
     *
     * <p>Read from the database rather than from the security context, because this service is also called
     * from paths that have no request bound to them, such as a scheduled execution, and because a role
     * revoked a moment ago must take effect immediately.
     */
    private boolean isAdmin(String userId) {
        return users.findById(userId).map(this::hasAdminRole).orElseGet(() -> {
            log.debug("Authorization check for unknown user {}", userId);
            return false;
        });
    }

    private boolean hasAdminRole(User user) {
        return user.isUsable() && user.getRoles().contains(Role.ADMIN);
    }
}
