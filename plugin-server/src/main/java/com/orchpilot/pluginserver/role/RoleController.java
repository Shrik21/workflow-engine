package com.orchpilot.pluginserver.role;

import com.orchpilot.pluginserver.audit.SecurityAuditLog;
import com.orchpilot.pluginserver.audit.SecurityAuditService;
import com.orchpilot.pluginserver.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Roles, and the permissions they grant.
 *
 * <p>Each role reports how many accounts hold it, because the question anybody asks before editing a role is
 * "who does this affect", and answering it should not require a second screen.
 */
@RestController
@RequestMapping("/api/roles")
@SecurityRequirement(name = "bearer")
@Tag(name = "Roles", description = "Roles and their permissions")
public class RoleController {

    private final RoleService roles;
    private final UserRepository users;
    private final SecurityAuditService audit;

    public RoleController(RoleService roles, UserRepository users, SecurityAuditService audit) {
        this.roles = roles;
        this.users = users;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_READ')")
    @Operation(summary = "Every role")
    public List<RoleView> list() {
        return roles.findAll().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_READ')")
    @Operation(summary = "One role")
    public RoleView get(@PathVariable String id) {
        return view(roles.requireById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_CREATE')")
    @Operation(summary = "Create a role",
            description = "Permission names are checked against the ones this registry implements. An "
                    + "unrecognised name is refused rather than dropped: a role that silently grants less "
                    + "than intended is worse than an error.")
    public ResponseEntity<RoleView> create(@Valid @RequestBody RoleRequest request,
                                           @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        Role created = roles.create(request.name(), request.description(), request.permissions());
        audit.recordOn(SecurityAuditLog.Action.ROLE_CREATED, actor(jwt), "ROLE", created.getId(), http,
                Map.of("name", created.getName(), "permissions", created.getPermissions()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_UPDATE')")
    @Operation(summary = "Update a role",
            description = "System roles may be edited. Denying that would force an installation to clone a "
                    + "shipped role to add one permission, and then maintain the clone.")
    public RoleView update(@PathVariable String id, @Valid @RequestBody RoleRequest request,
                           @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        Role updated = roles.update(id, request.description(), request.permissions());
        audit.recordOn(SecurityAuditLog.Action.ROLE_UPDATED, actor(jwt), "ROLE", id, http,
                Map.of("name", updated.getName(), "permissions", updated.getPermissions()));
        return view(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_DELETE')")
    @Operation(summary = "Delete a role",
            description = "Refused for a system role: deleting the role every account depends on locks "
                    + "everybody out of a running registry.")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                       HttpServletRequest http) {
        Role role = roles.requireById(id);
        roles.delete(id);
        audit.recordOn(SecurityAuditLog.Action.ROLE_DELETED, actor(jwt), "ROLE", id, http,
                Map.of("name", role.getName()));
        return ResponseEntity.noContent().build();
    }

    private RoleView view(Role role) {
        return new RoleView(role.getId(), role.getName(), role.getDescription(), role.getPermissions(),
                role.isSystemRole(), users.findByRolesContaining(role.getName()).size(),
                role.getCreatedAt(), role.getUpdatedAt());
    }

    private static String actor(Jwt jwt) {
        return jwt == null ? "system" : jwt.getClaimAsString("username");
    }

    /**
     * A role, as this API describes one.
     *
     * @param userCount how many accounts hold it, so the blast radius of an edit is visible before making it
     */
    public record RoleView(String id, String name, String description, Set<String> permissions,
                           boolean systemRole, int userCount, Instant createdAt, Instant updatedAt) {
    }

    /** What is supplied to create or replace a role. */
    public record RoleRequest(@NotBlank(message = "A role name is required") String name,
                              String description, Set<String> permissions) {
    }

}
