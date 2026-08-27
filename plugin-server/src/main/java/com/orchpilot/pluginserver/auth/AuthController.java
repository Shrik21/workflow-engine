package com.orchpilot.pluginserver.auth;

import com.orchpilot.pluginserver.audit.SecurityAuditLog;
import com.orchpilot.pluginserver.audit.SecurityAuditService;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.permission.PermissionService;
import com.orchpilot.pluginserver.security.AuthProperties;
import com.orchpilot.pluginserver.security.PasswordPolicy;
import com.orchpilot.pluginserver.security.RefreshCookies;
import com.orchpilot.pluginserver.user.User;
import com.orchpilot.pluginserver.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Signing in and out of the plugin registry.
 *
 * <h2>Independent of every other service</h2>
 *
 * These accounts, passwords, roles and tokens belong to this registry. Nothing here consults the workflow
 * platform, and a token it issued is not accepted. That separation is the point: publishing to this registry
 * distributes executable code to every engine that reads it, so the decision about who may do that is made
 * here.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Signing in to the plugin registry with its own accounts")
public class AuthController {

    private final AuthService auth;
    private final UserService users;
    private final PermissionService permissions;
    private final PasswordPolicy passwordPolicy;
    private final AuthProperties properties;
    private final SecurityAuditService audit;
    private final RefreshCookies cookies;

    public AuthController(AuthService auth, UserService users, PermissionService permissions,
                          PasswordPolicy passwordPolicy, AuthProperties properties,
                          SecurityAuditService audit, RefreshCookies cookies) {
        this.auth = auth;
        this.users = users;
        this.permissions = permissions;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
        this.audit = audit;
        this.cookies = cookies;
    }

    /**
     * Exchanges a username and password for a session.
     *
     * @param request the credentials
     * @param http    the request, for the audit trail
     * @return the session
     */
    @PostMapping("/login")
    @Operation(summary = "Sign in",
            description = """
                    Returns a short-lived access token and a rotating refresh token.

                    Every failure answers identically — wrong password, unknown username, disabled account, \
                    locked account — because distinguishing them tells somebody working through a list of \
                    names which ones are real. The audit trail records the distinction; this response does \
                    not.""")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "The credentials were not accepted")
    public ResponseEntity<AuthDtos.SessionResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                          HttpServletRequest http) {
        return session(auth.login(request.username(), request.password(), http));
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * @param request the refresh token
     * @param http    the request
     * @return a new session
     */
    @PostMapping("/refresh")
    @Operation(summary = "Renew a session",
            description = """
                    Consumes the presented refresh token and issues a new one. A token presented after it has \
                    already been rotated means two parties hold it, so every session for that account is \
                    revoked and both must sign in again.""")
    public ResponseEntity<AuthDtos.SessionResponse> refresh(
            @RequestBody(required = false) AuthDtos.RefreshRequest request, HttpServletRequest http) {
        // The cookie first: a browser cannot read an HttpOnly value to put it in a body, so for the console
        // this is the only place the token can come from. A body is accepted for clients that hold their own.
        String presented = cookies.read(http)
                .orElseGet(() -> request == null ? null : request.refreshToken());
        return session(auth.refresh(presented, http));
    }

    /**
     * Revokes a refresh token.
     *
     * @param request the token to revoke
     * @param jwt     the caller, for attribution
     * @param http    the request
     * @return no content
     */
    @PostMapping("/logout")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Sign out",
            description = "Revokes the refresh token. The access token is not revoked and remains valid "
                    + "until it expires, which is why access tokens are short-lived.")
    public ResponseEntity<Void> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request,
                                       @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        String presented = cookies.read(http)
                .orElseGet(() -> request == null ? null : request.refreshToken());
        auth.logout(presented, nameOf(jwt), http);

        // Clearing by the same name and path this registry set. The workflow platform's cookie shares the
        // host and path but not the name, so it is left exactly as it was: signing out here does not sign
        // anybody out of there.
        return ResponseEntity.noContent().header(cookies.header(), cookies.clear()).build();
    }

    /**
     * Who the caller is.
     *
     * @param jwt the caller's token
     * @return their account, roles and effective permissions
     */
    @GetMapping("/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "The signed-in account",
            description = "Read from the database rather than from the token, so a role changed a minute ago "
                    + "is reflected here even though the token still carries the old permissions.")
    public AuthDtos.UserView me(@AuthenticationPrincipal Jwt jwt) {
        User user = currentUser(jwt);
        return AuthDtos.UserView.of(user, permissions.getEffectivePermissionNames(user));
    }

    /**
     * Changes the caller's own password.
     *
     * @param request the current and new passwords
     * @param jwt     the caller
     * @param http    the request
     * @return no content
     */
    @PostMapping("/change-password")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Change your password",
            description = """
                    Requires the current password even though the caller is already authenticated, so a \
                    stolen access token cannot be turned into permanent control of the account.

                    Every other session is ended: a password change is usually a response to believing \
                    somebody else has it.""")
    @ApiResponse(responseCode = "204", description = "Changed; sign in again")
    @ApiResponse(responseCode = "422", description = "The new password does not meet the policy")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
                                               @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        User user = currentUser(jwt);
        users.changePassword(user, request.currentPassword(), request.newPassword());
        audit.record(SecurityAuditLog.Action.PASSWORD_CHANGED, user.getUsername(), user.getId(), true, http,
                Map.of("sessionsRevoked", true));
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates an account, when this registry accepts self-registration.
     *
     * @param request the account to create
     * @param http    the request
     * @return the new account
     */
    @PostMapping("/register")
    @Operation(summary = "Register",
            description = """
                    Disabled by default, and deliberately so: a plugin registry distributes executable code, \
                    and an account on it is not something to hand out on request.

                    When enabled, a new account receives the read-only viewer role. There is no configuration \
                    that makes registration grant anything more.""")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "403", description = "Registration is disabled on this registry")
    public ResponseEntity<AuthDtos.UserView> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                      HttpServletRequest http) {
        if (!auth.isRegistrationEnabled()) {
            throw PluginServerException.forbidden("REGISTRATION_DISABLED",
                    "This registry does not accept self-registration. Ask an administrator for an account.");
        }
        User created = users.create(new UserService.NewUser(
                request.username(), request.email(), request.firstName(), request.lastName(),
                request.password(),
                // The viewer role, always. Not configurable to anything else: a self-registered account that
                // could publish would make registration a way to distribute code.
                Set.of(auth.defaultRegistrationRole()), false, false), "self-registration");

        audit.recordOn(SecurityAuditLog.Action.USER_CREATED, created.getUsername(), "USER", created.getId(),
                http, Map.of("via", "self-registration", "roles", created.getRoles()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthDtos.UserView.of(created, permissions.getEffectivePermissionNames(created)));
    }

    /**
     * What a password must satisfy.
     *
     * @return the rules, so a form can state them before anything is typed
     */
    @GetMapping("/password-policy")
    @Operation(summary = "The password policy",
            description = "Public, because a sign-up or password-change form has to state the rules before "
                    + "anything is typed, and the rules are not a secret.")
    public AuthDtos.PasswordPolicyResponse passwordPolicy() {
        return new AuthDtos.PasswordPolicyResponse(passwordPolicy.describe(),
                properties.getPassword().getMinLength(), properties.getRegistration().isEnabled());
    }

    /**
     * Returns a session, putting the refresh token in this registry's cookie when that transport is on.
     *
     * <p>The token is then withheld from the body: returning it in both places would hand a script the value
     * the {@code HttpOnly} flag exists to hide.
     */
    private ResponseEntity<AuthDtos.SessionResponse> session(AuthService.Session session) {
        if (!cookies.isCookieTransport()) {
            return ResponseEntity.ok(AuthDtos.SessionResponse.of(session));
        }
        return ResponseEntity.ok()
                .header(cookies.header(), cookies.issue(session.refreshToken()))
                .body(AuthDtos.SessionResponse.withoutRefreshToken(session));
    }

    /** Loads the caller's account, which is where their current roles live. */
    private User currentUser(Jwt jwt) {
        if (jwt == null) {
            throw PluginServerException.badRequest("NOT_AUTHENTICATED", "Authentication is required.");
        }
        String username = jwt.getClaimAsString("username");
        return users.findByUsername(username)
                .orElseThrow(() -> PluginServerException.notFound("USER_NOT_FOUND",
                        "The account this token names no longer exists."));
    }

    private static String nameOf(Jwt jwt) {
        return jwt == null ? "anonymous" : jwt.getClaimAsString("username");
    }
}
