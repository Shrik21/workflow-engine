package com.orchpilot.workflow.access;

import java.util.Set;

/**
 * Decides what a user may do to a particular workflow.
 *
 * <p>The single authority for workflow-level authorization. Controllers, services and Spring Security SpEL
 * expressions all route through here rather than each re-deriving the rules, because a permission check
 * duplicated in three places is a permission check that will eventually disagree with itself.
 *
 * <p>{@link #hasPermission(String, String, WorkflowPermission)} is the real implementation; every other
 * method delegates to it. Adding a permission therefore needs no new method here at all.
 *
 * <p>Registered as the bean {@code workflowAuthorizationService} so it can be named in an annotation:
 *
 * <pre>{@code
 * @PreAuthorize("@workflowAuthorizationService.canExecute(authentication, #id)")
 * }</pre>
 */
public interface WorkflowAuthorizationService {

    /**
     * The primary check.
     *
     * <p>Resolution order, first match wins:
     * <ol>
     *   <li>ADMIN, or any role holding the corresponding system permission: allowed globally.</li>
     *   <li>The workflow's owner: allowed for {@link WorkflowPermission#ownerDefaults()}.</li>
     *   <li>Otherwise: the union of permissions from every enabled group that the user belongs to
     *       <em>and</em> that is attached to the workflow.</li>
     * </ol>
     *
     * <p>A workflow with no attached groups is reachable only by its owner and by an administrator. That is
     * deliberate: defaulting an unshared workflow to "everyone may view" would silently expose every
     * workflow created before groups existed.
     *
     * @param userId     the user, or {@code null} for an unauthenticated caller, which is always denied
     * @param workflowId the workflow
     * @param permission the capability being requested
     * @return whether the operation is allowed
     */
    boolean hasPermission(String userId, String workflowId, WorkflowPermission permission);

    /**
     * Every permission the user holds on a workflow.
     *
     * <p>Used by {@code GET /api/workflows/{id}/my-permissions} so the console can hide buttons the user
     * cannot use. That is a courtesy: the server still checks each operation independently.
     *
     * @param userId     the user
     * @param workflowId the workflow
     * @return the effective permission set, possibly empty
     */
    Set<WorkflowPermission> effectivePermissions(String userId, String workflowId);

    /**
     * The workflow ids a user may see.
     *
     * <p>Returned as a filter for the workflow list query, so unauthorised workflows are excluded by the
     * database rather than fetched and then hidden. Fetching everything and filtering afterwards leaks
     * through paging, totals and any endpoint that forgets the filter.
     *
     * @param userId the user
     * @return the ids, or empty when the user is an administrator and no filter should be applied
     */
    AccessScope visibleWorkflowScope(String userId);

    boolean canView(String userId, String workflowId);

    boolean canEdit(String userId, String workflowId);

    boolean canExecute(String userId, String workflowId);

    boolean canDelete(String userId, String workflowId);

    boolean canPublish(String userId, String workflowId);

    boolean canClone(String userId, String workflowId);

    boolean canViewExecution(String userId, String workflowId);

    boolean canCancelExecution(String userId, String workflowId);

    boolean canRetryExecution(String userId, String workflowId);

    /**
     * Whether a user may change which groups a workflow is shared with.
     *
     * <p>Requires ownership or ADMIN rather than {@code WORKFLOW_EDIT}. Otherwise anyone granted edit
     * through a group could attach their own groups and widen access indefinitely, which is privilege
     * escalation dressed up as an edit.
     *
     * @param userId     the user
     * @param workflowId the workflow
     * @return whether the access list may be changed
     */
    boolean canManageAccess(String userId, String workflowId);

    /**
     * How a workflow list should be filtered for one user.
     *
     * @param unrestricted true for an administrator, where no filter applies
     * @param ownerId      the user's id, for matching owned workflows
     * @param groupIds     the user's enabled group ids, for matching shared workflows
     */
    record AccessScope(boolean unrestricted, String ownerId, Set<String> groupIds) {

        /** @return whether the user can see nothing at all, so the query can be skipped entirely */
        public boolean isEmpty() {
            return !unrestricted && ownerId == null && groupIds.isEmpty();
        }
    }
}
