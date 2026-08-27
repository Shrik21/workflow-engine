package com.orchpilot.workflow.service;

import com.orchpilot.workflow.access.WorkflowAuthorizationService;
import com.orchpilot.workflow.access.WorkflowPermission;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.model.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Who may do what to a particular workflow.
 *
 * <p>The permission check has already happened by the time anything reaches here: the security
 * configuration decided whether the caller may edit workflows at all. This class answers the question a
 * path rule cannot, which is whether they may edit <em>this</em> one.
 *
 * <p>Enforced in the service layer rather than in the controllers, because a workflow is reached by more
 * routes than a controller annotation covers: publishing, executing, archiving and bulk execution control
 * all touch a definition, and each would need its own annotation to be equally protected. One class, called
 * from the service methods, cannot be bypassed by adding an endpoint.
 *
 * <p><b>Why one class.</b> This is also the seam multi-tenancy would use. {@link Workflow} and
 * {@code User} already carry a nullable {@code tenantId}; adding isolation later means adding one predicate
 * here rather than auditing every query and every check in the code base. Scattering ownership checks
 * inline is what makes that retrofit impossible.
 */
@Component
public class WorkflowAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAccessPolicy.class);

    /** The single authority for workflow permissions. This class adds only the tenant predicate. */
    private final WorkflowAuthorizationService authorization;

    public WorkflowAccessPolicy(WorkflowAuthorizationService authorization) {
        this.authorization = authorization;
    }

    /**
     * Whether the caller may see a workflow.
     *
     * <p>Reading is intentionally broader than writing: a USER can view any workflow in the installation,
     * which is what makes a shared library of workflows useful. Tighten this to ownership if an installation
     * needs it; it is one condition, in one place.
     *
     * @param workflow the workflow
     * @return whether the current caller may read it
     */
    public boolean canView(Workflow workflow) {
        return isSameTenant(workflow) && authorization.hasPermission(
                CurrentUser.userId().orElse(null), workflow.getId(), WorkflowPermission.WORKFLOW_VIEW);
    }

    /**
     * Whether the caller may modify a workflow.
     *
     * <p>Delegates to the group-based authorization service, which is the single authority. This class used
     * to decide ownership itself, and the result was two layers that disagreed: a user granted
     * {@code WORKFLOW_EDIT} through a group passed the controller's check and was then refused here, because
     * this policy only understood "owner or administrator". Two checks that answer the same question
     * differently will always eventually diverge, so there is now only one.
     *
     * @param workflow the workflow
     * @return whether the current caller may edit it
     */
    public boolean canEdit(Workflow workflow) {
        return isSameTenant(workflow) && authorization.hasPermission(
                CurrentUser.userId().orElse(null), workflow.getId(), WorkflowPermission.WORKFLOW_EDIT);
    }

    /**
     * @param workflow the workflow
     * @return whether the current caller may delete it
     */
    public boolean canDelete(Workflow workflow) {
        return isSameTenant(workflow) && authorization.hasPermission(
                CurrentUser.userId().orElse(null), workflow.getId(), WorkflowPermission.WORKFLOW_DELETE);
    }

    /**
     * @param workflow the workflow
     * @return whether the current caller may publish it, which makes it executable by others
     */
    public boolean canPublish(Workflow workflow) {
        return isSameTenant(workflow) && authorization.hasPermission(
                CurrentUser.userId().orElse(null), workflow.getId(), WorkflowPermission.WORKFLOW_PUBLISH);
    }

    /**
     * Whether the caller may run a workflow.
     *
     * <p>Not restricted to the owner. A published workflow is meant to be used, and requiring ownership to
     * run one would make publishing pointless.
     *
     * @param workflow the workflow
     * @return whether the current caller may execute it
     */
    public boolean canExecute(Workflow workflow) {
        return isSameTenant(workflow) && authorization.hasPermission(
                CurrentUser.userId().orElse(null), workflow.getId(), WorkflowPermission.WORKFLOW_EXECUTE);
    }

    /**
     * Asserts edit access.
     *
     * @param workflow the workflow
     * @throws AccessDeniedException when the caller may not edit it
     */
    public void requireEdit(Workflow workflow) {
        if (!canEdit(workflow)) {
            deny("edit", workflow);
        }
    }

    /**
     * Asserts delete access.
     *
     * @param workflow the workflow
     * @throws AccessDeniedException when the caller may not delete it
     */
    public void requireDelete(Workflow workflow) {
        if (!canDelete(workflow)) {
            deny("delete", workflow);
        }
    }

    /**
     * Asserts publish access.
     *
     * @param workflow the workflow
     * @throws AccessDeniedException when the caller may not publish it
     */
    public void requirePublish(Workflow workflow) {
        if (!canPublish(workflow)) {
            deny("publish", workflow);
        }
    }

    /**
     * Stamps ownership onto a new workflow.
     *
     * <p>Owner and creator start out the same and then diverge: the creator is history and never changes,
     * while ownership can be transferred. The tenant is copied from the creator, which is what makes the
     * tenant predicate above meaningful once multi-tenancy is switched on.
     *
     * @param workflow the workflow being created
     */
    public void stampOwnership(Workflow workflow) {
        CurrentUser.principal().ifPresent(principal -> {
            workflow.setOwnerId(principal.getUserId());
            workflow.setCreatedBy(principal.getUsername());
            workflow.setUpdatedBy(principal.getUsername());
            workflow.setTenantId(principal.getTenantId());
        });
    }

    /**
     * Records who last changed a workflow, leaving ownership alone.
     *
     * @param workflow the workflow being updated
     */
    public void stampUpdate(Workflow workflow) {
        CurrentUser.username().ifPresent(workflow::setUpdatedBy);
        // An older workflow saved before ownership existed adopts its first authenticated editor, so it
        // does not stay permanently un-editable by anyone but an administrator.
        if (workflow.getOwnerId() == null) {
            CurrentUser.userId().ifPresent(workflow::setOwnerId);
        }
    }

    private boolean isOwner(Workflow workflow) {
        Optional<String> caller = CurrentUser.userId();
        return caller.isPresent() && caller.get().equals(workflow.getOwnerId());
    }

    /**
     * Tenant isolation, inert until tenants exist.
     *
     * <p>Both sides are null in a single-tenant installation, so this is true and costs nothing. It is
     * written now so the check exists at every call site the day a tenant id starts being populated.
     */
    private boolean isSameTenant(Workflow workflow) {
        String workflowTenant = workflow.getTenantId();
        String callerTenant = CurrentUser.principal().map(AuthPrincipal::getTenantId).orElse(null);
        if (workflowTenant == null || callerTenant == null) {
            return true;
        }
        return workflowTenant.equals(callerTenant);
    }

    private void deny(String action, Workflow workflow) {
        log.info("Denied {} on workflow {} for {}", action, workflow.getId(), CurrentUser.actorOrSystem());
        // No mention of the owner: the caller is not entitled to learn who that is.
        throw new AccessDeniedException("You do not have permission to " + action + " this workflow");
    }
}
