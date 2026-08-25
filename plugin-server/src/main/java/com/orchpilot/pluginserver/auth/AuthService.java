package com.orchpilot.pluginserver.auth;

import com.orchpilot.pluginserver.audit.SecurityAuditLog;
import com.orchpilot.pluginserver.audit.SecurityAuditService;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.permission.PermissionService;
import com.orchpilot.pluginserver.security.AuthProperties;
import com.orchpilot.pluginserver.security.JwtTokenService;
import com.orchpilot.pluginserver.user.User;
import com.orchpilot.pluginserver.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Signing in, refreshing and signing out.
 *
 * <h2>One answer for every failure</h2>
 *
 * A wrong password, an unknown username, a disabled account and a locked one all produce the same message and
 * the same status. Distinguishing them tells somebody working through a list of names which ones are real and
 * which are worth continuing to guess at. The audit trail records the distinction; the response does not.
 *
 * <h2>Timing</h2>
 *
 * An unknown username still costs a password hash. Skipping it would make a failed lookup measurably faster
 * than a failed password, which turns response time into a username oracle. The dummy hash below exists for
 * that reason alone.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real Argon2id hash of a value nobody knows, verified against when no account matches.
     *
     * <p>Its only purpose is to spend the same time an existing account would. It cannot match anything: the
     * password it encodes was random and discarded.
     */
    private static final String DUMMY_HASH =
            "{argon2}$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHR2YWx1ZQ$"
                    + "L5G3xLGVWpXQ8Z0mVYqk1tXqFhVQXW0EJqf0VVQ0M2s";

    private final UserService users;
    private final PermissionService permissions;
    private final JwtTokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditService audit;
    private final AuthProperties properties;

    public AuthService(UserService users, PermissionService permissions, JwtTokenService tokens,
                       RefreshTokenService refreshTokens, PasswordEncoder passwordEncoder,
                       SecurityAuditService audit, AuthProperties properties) {
        this.users = users;
        this.permissions = permissions;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * A signed-in session.
     *
     * @param accessToken        the JWT
     * @param refreshToken       the rotating credential; returned once and stored only as a hash
     * @param expiresIn          access-token lifetime in seconds
     * @param user               who signed in
     * @param roles              their roles
     * @param permissions        their effective permissions
     * @param mustChangePassword whether they must set a new password before doing anything else
     */
    public record Session(String accessToken, String refreshToken, long expiresIn, User user,
                          Set<String> roles, Set<String> permissions, boolean mustChangePassword) {
    }

    /**
     * Authenticates a username and password.
     *
     * @param username the account
     * @param password the candidate password
     * @param request  the request, for the audit trail and the session record
     * @return the session
     * @throws PluginServerException with one message for every kind of failure
     */
    public Session login(String username, String password, HttpServletRequest request) {
        Optional<User> found = users.findByUsername(username);

        if (found.isEmpty()) {
            // Spend the time an existing account would, then fail identically.
            passwordEncoder.matches(password, DUMMY_HASH);
            audit.record(SecurityAuditLog.Action.LOGIN_FAILURE, username, null, false, request,
                    Map.of("reason", "no such account"));
            throw invalidCredentials();
        }

        User user = found.get();

        if (users.settleLock(user)) {
            audit.record(SecurityAuditLog.Action.LOGIN_BLOCKED, username, user.getId(), false, request,
                    Map.of("reason", "account locked", "until", String.valueOf(user.getLockedUntil())));
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            audit.record(SecurityAuditLog.Action.LOGIN_BLOCKED, username, user.getId(), false, request,
                    Map.of("reason", "account disabled"));
            throw invalidCredentials();
        }
        if (user.isServiceAccount()) {
            // A machine identity has no business signing in through the console.
            audit.record(SecurityAuditLog.Action.LOGIN_BLOCKED, username, user.getId(), false, request,
                    Map.of("reason", "service account"));
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            boolean locked = users.recordFailedLogin(user);
            audit.record(SecurityAuditLog.Action.LOGIN_FAILURE, username, user.getId(), false, request,
                    Map.of("reason", "wrong password", "attempts", user.getFailedLoginAttempts()));
            if (locked) {
                audit.record(SecurityAuditLog.Action.ACCOUNT_LOCKED, username, user.getId(), false, request,
                        Map.of("until", String.valueOf(user.getLockedUntil())));
            }
            throw invalidCredentials();
        }

        users.recordSuccessfulLogin(user);
        audit.record(SecurityAuditLog.Action.LOGIN_SUCCESS, username, user.getId(), true, request, Map.of());
        return issue(user, request);
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * @param presented the refresh token
     * @param request   the request
     * @return a new session
     */
    public Session refresh(String presented, HttpServletRequest request) {
        // Validate first: which account a token belongs to is only knowable from the stored record, so the
        // account cannot be loaded until the token has been recognised.
        RefreshTokenService.RefreshOutcome outcome = refreshTokens.validate(presented, request);

        if (outcome instanceof RefreshTokenService.RefreshOutcome.ReuseDetected reuse) {
            log.warn("Refresh token reuse for '{}'; that account's sessions have been revoked",
                    reuse.username());
            throw invalidRefreshToken();
        }
        if (outcome instanceof RefreshTokenService.RefreshOutcome.Rejected rejected) {
            audit.record(SecurityAuditLog.Action.TOKEN_REFRESH, null, null, false, request,
                    Map.of("reason", rejected.reason()));
            throw invalidRefreshToken();
        }

        RefreshTokenRecord record = ((RefreshTokenService.RefreshOutcome.Valid) outcome).record();
        User user = users.require(record.getUserId());

        if (!user.isEnabled() || users.settleLock(user)) {
            // The token is live but the account is not. Ending every session is the honest response: the
            // holder should not be able to keep renewing access to an account somebody disabled.
            refreshTokens.revokeAllFor(user.getId(), "account is disabled or locked");
            audit.record(SecurityAuditLog.Action.TOKEN_REFRESH, user.getUsername(), user.getId(), false,
                    request, Map.of("reason", "account not usable"));
            throw invalidRefreshToken();
        }
        if (refreshTokens.predatesCredentialChange(record, user)) {
            audit.record(SecurityAuditLog.Action.TOKEN_REFRESH, user.getUsername(), user.getId(), false,
                    request, Map.of("reason", "issued before the password changed"));
            throw invalidRefreshToken();
        }

        RefreshTokenService.IssuedRefreshToken replacement = refreshTokens.rotate(record, user, request);
        Set<String> granted = permissions.getEffectivePermissionNames(user);
        JwtTokenService.IssuedToken access = tokens.issue(user, user.getRoles(), granted);
        audit.record(SecurityAuditLog.Action.TOKEN_REFRESH, user.getUsername(), user.getId(), true, request,
                Map.of());

        return new Session(access.value(), replacement.value(), access.expiresIn(), user, user.getRoles(),
                granted, user.isMustChangePassword());
    }

    /**
     * Revokes a refresh token.
     *
     * @param presented the token to revoke; a token nobody recognises is not an error
     * @param actor     who asked, for the trail
     * @param request   the request
     */
    public void logout(String presented, String actor, HttpServletRequest request) {
        if (presented != null && !presented.isBlank()) {
            refreshTokens.revoke(presented);
        }
        audit.record(SecurityAuditLog.Action.LOGOUT, actor, null, true, request, Map.of());
    }

    /**
     * Issues a session for an account that has already been authenticated.
     *
     * @param user    the account
     * @param request the request
     * @return the session
     */
    public Session issue(User user, HttpServletRequest request) {
        Set<String> granted = permissions.getEffectivePermissionNames(user);
        JwtTokenService.IssuedToken access = tokens.issue(user, user.getRoles(), granted);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokens.issue(user, request);
        return new Session(access.value(), refresh.value(), access.expiresIn(), user, user.getRoles(),
                granted, user.isMustChangePassword());
    }

    /** @return whether this registry accepts self-registration at all */
    public boolean isRegistrationEnabled() {
        return properties.getRegistration().isEnabled();
    }

    /** @return the role a self-registered account receives */
    public String defaultRegistrationRole() {
        return properties.getRegistration().getDefaultRole();
    }


    private static PluginServerException invalidCredentials() {
        return PluginServerException.unauthorized("INVALID_CREDENTIALS",
                "That username and password were not accepted.");
    }

    private static PluginServerException invalidRefreshToken() {
        return PluginServerException.unauthorized("INVALID_REFRESH_TOKEN",
                "The session could not be renewed. Sign in again.");
    }
}
