package com.orchpilot.workflow.access.dto;

import com.orchpilot.workflow.access.Group;
import com.orchpilot.workflow.access.WorkflowPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Request and response shapes for group administration.
 *
 * <p>Grouped in one file because they are small, change together and are used only by the group controller.
 *
 * <p>Permissions bind as the {@link WorkflowPermission} enum rather than as strings, so an unknown value is
 * rejected by deserialisation with a 400 before any code runs. That satisfies "do not allow arbitrary
 * permission strings" structurally rather than with a validation pass someone could forget to call.
 */
public final class GroupDtos {

    private GroupDtos() {
    }

    /**
     * Creates a group.
     *
     * @param name        display name, unique
     * @param description what the group is for
     * @param permissions the workflow permissions it grants; may be empty and filled in later
     * @param enabled     whether it grants anything yet; defaults to true when null
     */
    public record CreateGroup(
            @NotBlank(message = "Group name is required")
            @Size(min = 2, max = 100, message = "Group name must be between 2 and 100 characters")
            String name,

            @Size(max = 500, message = "Description is too long")
            String description,

            Set<WorkflowPermission> permissions,
            Boolean enabled) {
    }

    /**
     * Updates a group's name, description or status. Permissions have their own endpoint so that granting
     * capability is a separate, separately audited action from renaming.
     */
    public record UpdateGroup(
            @Size(min = 2, max = 100, message = "Group name must be between 2 and 100 characters")
            String name,
            @Size(max = 500, message = "Description is too long")
            String description,
            Boolean enabled) {
    }

    /**
     * Replaces a group's permissions.
     *
     * @param permissions the complete new set, so the caller states the intended final state rather than a
     *                    delta that could combine unexpectedly with a concurrent edit
     */
    public record UpdatePermissions(
            @NotNull(message = "Permissions are required; send an empty list to revoke all")
            Set<WorkflowPermission> permissions) {
    }

    /**
     * A group as returned by the API.
     *
     * @param memberCount resolved separately from {@code group_members}, which is why membership is not a
     *                    field on the group document
     */
    public record GroupResponse(
            String id,
            String name,
            String description,
            Set<WorkflowPermission> permissions,
            boolean enabled,
            long memberCount,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {

        public static GroupResponse from(Group group, long memberCount) {
            return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                    group.permissionSet(), group.isEnabled(), memberCount,
                    group.getCreatedBy(), group.getCreatedAt(), group.getUpdatedAt());
        }
    }

    /**
     * The minimal shape for a picker.
     *
     * <p>Deliberately omits member counts, membership and audit fields: the workflow designer's group
     * dropdown is reachable by any workflow owner, and it does not need to know who is in a group in order
     * to share a workflow with it.
     */
    public record GroupSummary(String id, String name, String description, boolean enabled) {

        public static GroupSummary from(Group group) {
            return new GroupSummary(group.getId(), group.getName(), group.getDescription(), group.isEnabled());
        }
    }

    /**
     * A group member, for the membership screen.
     *
     * @param userId   the member
     * @param username their login name
     * @param joinedAt when they were added
     * @param addedBy  who added them
     */
    public record GroupMember(
            String userId,
            String username,
            String email,
            String displayName,
            boolean enabled,
            Instant joinedAt,
            String addedBy) {
    }

    /** Sets the groups a workflow is shared with. */
    public record UpdateWorkflowAccess(
            @NotNull(message = "groupIds is required; send an empty list to remove all access")
            List<String> groupIds) {
    }

    /**
     * The groups a workflow is shared with.
     *
     * @param unresolvedGroupIds ids attached to the workflow that no longer exist, surfaced rather than
     *                           hidden so an operator can see why access is not behaving as expected
     */
    public record WorkflowAccessResponse(
            String workflowId,
            List<GroupSummary> groups,
            List<String> unresolvedGroupIds,
            String ownerId) {
    }

    /**
     * What the current user may do to a workflow, for the console to gate its buttons.
     *
     * <p>For presentation only. The server re-checks every operation, so a client that ignored this and
     * called the endpoint anyway would still be refused.
     */
    public record MyPermissions(
            String workflowId,
            Set<WorkflowPermission> permissions,
            List<GroupSummary> groups,
            boolean owner,
            boolean admin) {
    }
}
