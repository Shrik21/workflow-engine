package com.orchpilot.workflow.access;

import com.orchpilot.workflow.access.dto.GroupDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Group administration.
 *
 * <p>Everything except the picker feed requires ADMIN: a user who could edit groups could grant themselves
 * any permission on any workflow, which would make the whole model decorative.
 *
 * <p>{@code /available} is the exception and is open to any authenticated user. Sharing a workflow you own
 * means choosing a group, so a workflow owner must be able to list group names. It returns only id, name,
 * description and status, never membership.
 */
@RestController
@RequestMapping("/api/groups")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Groups", description = "Group-based workflow access control. Managing groups requires ADMIN.")
public class GroupController {

    private final GroupService service;

    public GroupController(GroupService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List groups with member counts")
    public List<GroupDtos.GroupResponse> list(@RequestParam(required = false) String search) {
        return service.list(search);
    }

    @GetMapping("/available")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Groups for a picker",
            description = "Enabled groups only, with no membership information. Open to any authenticated "
                    + "user because sharing a workflow you own requires choosing a group by name.")
    public List<GroupDtos.GroupSummary> available() {
        return service.available();
    }

    @GetMapping("/permissions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The permission catalogue",
            description = "Every workflow permission with its label and category, so the group editor renders "
                    + "from the server's list rather than a hardcoded copy that drifts.")
    public Map<String, List<Map<String, String>>> permissionCatalogue() {
        Map<String, List<Map<String, String>>> catalogue = new java.util.LinkedHashMap<>();
        WorkflowPermission.byCategory().forEach((category, permissions) ->
                catalogue.put(category, permissions.stream()
                        .map(permission -> Map.of("name", permission.name(), "label", permission.label()))
                        .toList()));
        return catalogue;
    }

    @GetMapping("/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get one group")
    public GroupDtos.GroupResponse get(@PathVariable String groupId) {
        return service.get(groupId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a group",
            description = "Permissions bind as an enum, so an unrecognised permission name is rejected with "
                    + "400 before any code runs.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "A group with that name already exists")
    public ResponseEntity<GroupDtos.GroupResponse> create(@Valid @RequestBody GroupDtos.CreateGroup request,
                                                          HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request, httpRequest));
    }

    @PutMapping("/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename, describe, enable or disable a group",
            description = "Disabling revokes everything the group grants immediately, without deleting it or "
                    + "losing its membership.")
    public GroupDtos.GroupResponse update(@PathVariable String groupId,
                                          @Valid @RequestBody GroupDtos.UpdateGroup request,
                                          HttpServletRequest httpRequest) {
        return service.update(groupId, request, httpRequest);
    }

    @GetMapping("/{groupId}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "A group's permissions")
    public java.util.Set<WorkflowPermission> permissions(@PathVariable String groupId) {
        return service.get(groupId).permissions();
    }

    @PutMapping("/{groupId}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a group's permissions",
            description = "Takes effect immediately for every member on every attached workflow, because "
                    + "permissions are read from the database per request and never cached in the token.")
    public GroupDtos.GroupResponse updatePermissions(@PathVariable String groupId,
                                                     @Valid @RequestBody GroupDtos.UpdatePermissions request,
                                                     HttpServletRequest httpRequest) {
        return service.updatePermissions(groupId, request.permissions(), httpRequest);
    }

    @DeleteMapping("/{groupId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a group",
            description = "Also removes its memberships and detaches it from every workflow, so no dangling "
                    + "reference is left behind.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    public ResponseEntity<Void> delete(@PathVariable String groupId, HttpServletRequest httpRequest) {
        service.delete(groupId, httpRequest);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ members

    @GetMapping("/{groupId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List a group's members")
    public List<GroupDtos.GroupMember> members(@PathVariable String groupId) {
        return service.members(groupId);
    }

    @PostMapping("/{groupId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a member",
            description = "Idempotent: adding an existing member succeeds and changes nothing.")
    @ApiResponse(responseCode = "204", description = "The user is a member")
    public ResponseEntity<Void> addMember(@PathVariable String groupId, @PathVariable String userId,
                                          HttpServletRequest httpRequest) {
        service.addMember(groupId, userId, httpRequest);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a member",
            description = "Access is lost on the member's next request; nothing is cached and no token needs "
                    + "to expire first.")
    @ApiResponse(responseCode = "204", description = "The user is not a member")
    public ResponseEntity<Void> removeMember(@PathVariable String groupId, @PathVariable String userId,
                                             HttpServletRequest httpRequest) {
        service.removeMember(groupId, userId, httpRequest);
        return ResponseEntity.noContent().build();
    }
}
