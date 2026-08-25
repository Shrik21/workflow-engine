package com.orchpilot.workflow.admin.controller;

import com.orchpilot.workflow.admin.dto.AdminUserRequests;
import com.orchpilot.workflow.admin.service.UserAdminService;
import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditLog;
import com.orchpilot.workflow.audit.SecurityAuditLogRepository;
import com.orchpilot.workflow.auth.dto.UserResponse;
import com.orchpilot.workflow.auth.model.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

/**
 * User administration.
 *
 * <p>Authorised twice on purpose. The security configuration already requires the ADMIN role for
 * {@code /api/admin/**}, and each method additionally asserts the specific permission it needs. The
 * duplication is deliberate defence in depth: these endpoints create accounts and grant privileges, and a
 * future refactor that loosens one path rule should not silently open all of them.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User administration", description = "Manage accounts, roles and account status. ADMIN only.")
public class UserAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserAdminService users;
    private final SecurityAuditLogRepository auditLogs;

    public UserAdminController(UserAdminService users, SecurityAuditLogRepository auditLogs) {
        this.users = users;
        this.auditLogs = auditLogs;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Operation(summary = "List users", description = "Password hashes are never included in any response.")
    @ApiResponse(responseCode = "200", description = "A page of users")
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator")
    public Page<UserResponse> list(@RequestParam(required = false) String search,
                                   @RequestParam(required = false) Role role,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        PageRequest request = PageRequest.of(Math.max(0, page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return users.list(search, role, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Operation(summary = "Get one user")
    public UserResponse get(@PathVariable String id) {
        return users.get(id);
    }

    @GetMapping("/{id}/sessions")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Operation(summary = "Count a user's live sessions",
            description = "How many unrevoked refresh tokens the account holds, which is how many devices "
                    + "can currently obtain a fresh access token.")
    public java.util.Map<String, Object> sessions(@PathVariable String id) {
        return java.util.Map.of("userId", id, "liveSessions", users.liveSessionCount(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    @Operation(summary = "Create a user",
            description = "Unlike self-registration, roles may be assigned here. The initial password is "
                    + "validated against the policy and hashed immediately.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "Username or email already taken")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody AdminUserRequests.CreateUser request,
                                               HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.create(request, httpRequest));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_EDIT')")
    @Operation(summary = "Update a profile",
            description = "Cannot change roles or passwords; those have their own endpoints so each is "
                    + "separately authorised and separately audited.")
    public UserResponse update(@PathVariable String id,
                               @Valid @RequestBody AdminUserRequests.UpdateUser request,
                               HttpServletRequest httpRequest) {
        return users.update(id, request, httpRequest);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('USER_EDIT')")
    @Operation(summary = "Replace a user's roles",
            description = "Revokes the user's sessions so the new authorities apply from the next sign-in. "
                    + "Refused when it would remove the last administrator.")
    @ApiResponse(responseCode = "409", description = "Would leave the platform with no administrator")
    public UserResponse updateRoles(@PathVariable String id,
                                    @Valid @RequestBody AdminUserRequests.UpdateRoles request,
                                    HttpServletRequest httpRequest) {
        return users.updateRoles(id, request.roles(), httpRequest);
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USER_EDIT')")
    @Operation(summary = "Enable or disable an account",
            description = "Disabling revokes every session immediately, so access stops at once rather than "
                    + "when the current token expires.")
    public UserResponse updateStatus(@PathVariable String id,
                                     @Valid @RequestBody AdminUserRequests.UpdateStatus request,
                                     HttpServletRequest httpRequest) {
        return users.updateStatus(id, request, httpRequest);
    }

    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('USER_EDIT')")
    @Operation(summary = "Lock an account",
            description = "The administrative lock, separate from the temporary lockout the brute-force "
                    + "throttle applies. Only an administrator can clear it.")
    public UserResponse lock(@PathVariable String id, HttpServletRequest httpRequest) {
        return users.setLocked(id, true, httpRequest);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('USER_EDIT')")
    @Operation(summary = "Unlock an account")
    public UserResponse unlock(@PathVariable String id, HttpServletRequest httpRequest) {
        return users.setLocked(id, false, httpRequest);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    @Operation(summary = "Delete an account",
            description = "Prefer disabling, which preserves the account while removing access. The audit "
                    + "trail survives deletion either way.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "409", description = "Would leave the platform with no administrator")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest httpRequest) {
        users.delete(id, httpRequest);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Operation(summary = "Read the security audit trail",
            description = "Sign-ins, failures, token refreshes, role changes and access denials. Contains no "
                    + "passwords, hashes or tokens by construction.")
    public Page<SecurityAuditLog> audit(@RequestParam(required = false) String userId,
                                        @RequestParam(required = false) SecurityAuditEvent event,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        PageRequest request = PageRequest.of(Math.max(0, page), clampSize(size));
        if (userId != null && !userId.isBlank()) {
            return auditLogs.findByUserIdOrderByAtDesc(userId, request);
        }
        if (event != null) {
            return auditLogs.findByEventOrderByAtDesc(event, request);
        }
        return auditLogs.findAllByOrderByAtDesc(request);
    }

    /** Caps the page size so a single request cannot ask for the whole collection. */
    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }
}
