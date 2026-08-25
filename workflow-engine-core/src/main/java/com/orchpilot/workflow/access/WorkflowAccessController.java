package com.orchpilot.workflow.access;

import com.orchpilot.workflow.access.dto.GroupDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which groups a workflow is shared with, and what the caller may do to it.
 *
 * <p>Separate from {@code WorkflowController} because sharing is an access-control operation with different
 * rules from editing a definition: changing the group list requires ownership or ADMIN, not
 * {@code WORKFLOW_EDIT}. Someone granted edit through a group must not be able to attach further groups,
 * which would let them widen their own access without an administrator ever being involved.
 */
@RestController
@RequestMapping("/api/workflows/{workflowId}")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Workflow access", description = "Group attachment and effective permissions for one workflow")
public class WorkflowAccessController {

    private final GroupService groups;

    public WorkflowAccessController(GroupService groups) {
        this.groups = groups;
    }

    @GetMapping("/access")
    @PreAuthorize("@workflowAuthorizationService.canView(authentication, #workflowId)")
    @Operation(summary = "The groups this workflow is shared with",
            description = "Requires WORKFLOW_VIEW. Also reports any attached group id that no longer "
                    + "resolves, so a deleted group does not become an invisible reason for missing access.")
    @ApiResponse(responseCode = "403", description = "No view permission on this workflow")
    public GroupDtos.WorkflowAccessResponse access(@PathVariable String workflowId) {
        return groups.accessOf(workflowId);
    }

    @PutMapping("/access")
    @PreAuthorize("@workflowAuthorizationService.canManageAccess(authentication, #workflowId)")
    @Operation(summary = "Set the groups this workflow is shared with",
            description = "Requires ownership or ADMIN, deliberately not WORKFLOW_EDIT. Replaces the whole "
                    + "list, so the caller states the intended final state. Unknown group ids are rejected "
                    + "rather than stored as access that silently grants nothing.")
    @ApiResponse(responseCode = "403", description = "Only the owner or an administrator may change sharing")
    @ApiResponse(responseCode = "404", description = "A supplied group id does not exist")
    public GroupDtos.WorkflowAccessResponse updateAccess(
            @PathVariable String workflowId,
            @Valid @RequestBody GroupDtos.UpdateWorkflowAccess request,
            HttpServletRequest httpRequest) {
        return groups.updateAccess(workflowId, request.groupIds(), httpRequest);
    }

    @GetMapping("/my-permissions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "What the calling user may do to this workflow",
            description = """
                    Returns the effective permissions, the groups that granted them, and whether the caller
                    is the owner or an administrator.

                    For presentation only. The console uses it to hide buttons a user cannot use; the server
                    re-checks every operation independently, so a client that ignored this and called the
                    endpoint anyway would still be refused.

                    Deliberately requires only authentication: a user with no access gets an empty permission
                    list rather than a 403, which is what lets the console render a clear "you do not have
                    access" state instead of an error.""")
    public GroupDtos.MyPermissions myPermissions(@PathVariable String workflowId) {
        return groups.myPermissions(workflowId);
    }
}
