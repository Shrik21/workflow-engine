package com.orchpilot.pluginserver.user;

import com.orchpilot.pluginserver.audit.SecurityAuditLog;
import com.orchpilot.pluginserver.audit.SecurityAuditService;
import com.orchpilot.pluginserver.auth.AuthDtos;
import com.orchpilot.pluginserver.permission.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Managing accounts on this registry.
 *
 * <h2>Disabling over deleting</h2>
 *
 * Both are offered and disabling is the one to reach for. A deleted account leaves every audit row that names
 * it pointing at somebody nobody can look up, which is precisely when those rows matter.
 *
 * <h2>Permissions, not roles, on each method</h2>
 *
 * {@code USER_READ} and {@code USER_CREATE} rather than {@code hasRole('PLUGIN_ADMIN')}, so an installation
 * that wants a role which can create accounts but not delete them can compose one without this file changing.
 */
@RestController
@RequestMapping("/api/users")
@SecurityRequirement(name = "bearer")
@Tag(name = "Users", description = "Accounts on this registry")
public class UserController {

    private final UserService users;
    private final PermissionService permissions;
    private final SecurityAuditService audit;

    public UserController(UserService users, PermissionService permissions, SecurityAuditService audit) {
        this.users = users;
        this.permissions = permissions;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    @Operation(summary = "Every account")
    public List<AuthDtos.UserView> list() {
        return users.findAll().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_READ')")
    @Operation(summary = "One account")
    public AuthDtos.UserView get(@PathVariable String id) {
        return view(users.require(id));
    }

    /**
     * Creates an account.
     *
     * @param request what to create
     * @param jwt     who is creating it
     * @param http    the request
     * @return the new account
     */
    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    @Operation(summary = "Create an account",
            description = "The password is hashed immediately and never stored or returned. Set "
                    + "mustChangePassword when handing over a password somebody else chose.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "The username or email is already in use")
    @ApiResponse(responseCode = "422", description = "The password does not meet the policy")
    public ResponseEntity<AuthDtos.UserView> create(@Valid @RequestBody CreateUserRequest request,
                                                    @AuthenticationPrincipal Jwt jwt,
                                                    HttpServletRequest http) {
        User created = users.create(new UserService.NewUser(request.username(), request.email(),
                request.firstName(), request.lastName(), request.password(), request.roles(),
                request.serviceAccount() != null && request.serviceAccount(),
                request.mustChangePassword() == null || request.mustChangePassword()), actor(jwt));

        audit.recordOn(SecurityAuditLog.Action.USER_CREATED, actor(jwt), "USER", created.getId(), http,
                Map.of("username", created.getUsername(), "roles", created.getRoles()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    @Operation(summary = "Update an account",
            description = "Not the password: an administrator resets it, and the holder changes it.")
    public AuthDtos.UserView update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request,
                                    @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        User updated = users.update(id, new UserService.UpdateUser(request.email(), request.firstName(),
                request.lastName(), request.roles()));
        audit.recordOn(SecurityAuditLog.Action.USER_UPDATED, actor(jwt), "USER", id, http,
                Map.of("roles", updated.getRoles()));
        return view(updated);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    @Operation(summary = "Enable an account")
    public AuthDtos.UserView enable(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                    HttpServletRequest http) {
        User updated = users.setEnabled(id, true);
        audit.recordOn(SecurityAuditLog.Action.USER_ENABLED, actor(jwt), "USER", id, http, Map.of());
        return view(updated);
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    @Operation(summary = "Disable an account",
            description = "Revokes every session it holds. Preferred over deletion: the audit trail keeps "
                    + "naming somebody who can still be looked up.")
    public AuthDtos.UserView disable(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                     HttpServletRequest http) {
        User updated = users.setEnabled(id, false);
        audit.recordOn(SecurityAuditLog.Action.USER_DISABLED, actor(jwt), "USER", id, http,
                Map.of("sessionsRevoked", true));
        return view(updated);
    }

    /**
     * Sets a temporary password.
     *
     * @param id      which account
     * @param request the new password
     * @param jwt     who is resetting it
     * @param http    the request
     * @return the account
     */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    @Operation(summary = "Reset a password",
            description = "The account must change it at next sign-in and every session is revoked: whoever "
                    + "set it has seen it, so it is a one-time credential rather than a password.")
    public AuthDtos.UserView resetPassword(@PathVariable String id,
                                           @Valid @RequestBody ResetPasswordRequest request,
                                           @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        User updated = users.resetPassword(id, request.newPassword());
        audit.recordOn(SecurityAuditLog.Action.PASSWORD_RESET, actor(jwt), "USER", id, http,
                Map.of("mustChangePassword", true, "sessionsRevoked", true));
        return view(updated);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    @Operation(summary = "Delete an account",
            description = "Refused for the only enabled administrator. Prefer disabling: deletion leaves "
                    + "audit rows naming somebody who can no longer be looked up.")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                       HttpServletRequest http) {
        users.delete(id);
        audit.recordOn(SecurityAuditLog.Action.USER_DELETED, actor(jwt), "USER", id, http, Map.of());
        return ResponseEntity.noContent().build();
    }

    private AuthDtos.UserView view(User user) {
        return AuthDtos.UserView.of(user, permissions.getEffectivePermissionNames(user));
    }

    private static String actor(Jwt jwt) {
        return jwt == null ? "system" : jwt.getClaimAsString("username");
    }

    /** What an administrator supplies to create an account. */
    public record CreateUserRequest(
            @NotBlank String username,
            @NotBlank @Email(message = "A valid email address is required") String email,
            String firstName,
            String lastName,
            @NotBlank(message = "An initial password is required") String password,
            @NotEmpty(message = "At least one role is required") Set<String> roles,
            Boolean serviceAccount,
            Boolean mustChangePassword) {
    }

    /** What may be changed. Null means "leave alone". */
    public record UpdateUserRequest(@Email String email, String firstName, String lastName,
                                    Set<String> roles) {
    }

    /** A password reset by an administrator. */
    public record ResetPasswordRequest(@NotBlank String newPassword) {
    }
}
