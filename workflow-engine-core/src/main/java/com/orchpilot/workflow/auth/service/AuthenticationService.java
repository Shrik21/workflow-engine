package com.orchpilot.workflow.auth.service;

import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditService;
import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.dto.ChangePasswordRequest;
import com.orchpilot.workflow.auth.dto.LoginRequest;
import com.orchpilot.workflow.auth.dto.LoginResponse;
import com.orchpilot.workflow.auth.dto.RegisterRequest;
import com.orchpilot.workflow.auth.dto.UserResponse;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.CustomUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registration, login, refresh, logout and password change.
 *
 * <p>Login verifies the password here rather than delegating to an {@code AuthenticationManager}. That is a
 * considered choice: the flow needs to check the throttle before spending an Argon2id verification, burn
 * equivalent time when the account does not exist, keep every failure mode indistinguishable in the
 * response while recording the real one, and upgrade a legacy hash on success. Expressing that through a
 * chain of {@code AuthenticationProvider}s and exception translation obscures it; twenty lines of explicit
 * sequence does not.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private static final String SELF_REGISTERED = "self-registration";

    private final UserRepository users;
    private final CustomUserDetailsService lookup;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final LoginThrottleService throttle;
    private final SecurityAuditService audit;
    private final AuthProperties properties;

    public AuthenticationService(UserRepository users, CustomUserDetailsService lookup,
                                 PasswordService passwords, JwtService jwt,
                                 RefreshTokenService refreshTokens, LoginThrottleService throttle,
                                 SecurityAuditService audit, AuthProperties properties) {
        this.users = users;
        this.lookup = lookup;
        this.passwords = passwords;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.throttle = throttle;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * Creates an account from a self-registration.
     *
     * <p>Always assigns {@link Role#USER}. The request type has no roles field, so there is nothing here to
     * ignore or sanitise: privilege escalation through registration is impossible by construction rather
     * than by validation.
     *
     * @param request  submitted details
     * @param httpRequest current request, for audit context
     * @return the created user
     * @throws DuplicateAccountException when the username or email is taken
     * @throws PasswordPolicyException   when the password is too weak
     */
    public UserResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        if (!properties.getRegistration().isEnabled()) {
            throw OperationNotAllowedException.forbidden(
                    "Self-registration is disabled on this installation. Ask an administrator to create "
                            + "your account.");
        }

        String username = normalise(request.username());
        String email = normalise(request.email());

        // Checked up front for a clear message. The unique indexes are what actually guarantee it, and
        // the catch below handles the race between this check and the insert.
        if (users.existsByUsername(username)) {
            throw new DuplicateAccountException("username", "That username is already taken");
        }
        if (users.existsByEmail(email)) {
            throw new DuplicateAccountException("email", "An account with that email already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        // Validates the policy and hashes. The raw password is never assigned to a field.
        user.setPasswordHash(passwords.hash(request.password()));
        user.setFirstName(trimToNull(request.firstName()));
        user.setLastName(trimToNull(request.lastName()));
        user.setRoles(Set.of(Role.USER));
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setCreatedBy(SELF_REGISTERED);

        User saved = save(user);
        audit.success(SecurityAuditEvent.USER_REGISTERED, saved.getId(), saved.getUsername(), httpRequest,
                Map.of("roles", Set.of(Role.USER.name())));
        log.info("Registered user {} ({})", saved.getUsername(), saved.getId());
        return UserResponse.from(saved);
    }

    /**
     * Authenticates a user.
     *
     * @param request     credentials
     * @param httpRequest current request, for throttling and audit context
     * @return tokens and the user
     * @throws AuthenticationException on any failure, rendered generically
     */
    public AuthResult login(LoginRequest request, HttpServletRequest httpRequest) {
        String submitted = normalise(request.username());
        String ipAddress = clientAddress(httpRequest);

        // Checked before any hashing. A locked-out attacker must not be able to keep spending Argon2id
        // verifications, which would turn the throttle into a way to exhaust the server.
        if (throttle.isLockedOut(submitted, ipAddress)) {
            audit.failure(SecurityAuditEvent.ACCOUNT_LOCKED, null, submitted, "rate_limited", httpRequest);
            throw AuthenticationException.lockedOut(throttle.lockoutSeconds());
        }

        Optional<User> found = lookup.find(submitted);
        if (found.isEmpty()) {
            // Spend comparable time so response timing does not disclose that the account is missing.
            passwords.verifyDummy(request.password());
            return failLogin(submitted, null, "user_not_found", ipAddress, httpRequest);
        }

        User user = found.get();
        if (!passwords.verify(request.password(), user.getPasswordHash())) {
            return failLogin(submitted, user, "bad_credentials", ipAddress, httpRequest);
        }

        // Status is checked after the password, so a wrong password against a disabled account cannot be
        // distinguished from a wrong password against an active one.
        if (!user.isEnabled()) {
            return failLogin(submitted, user, "account_disabled", ipAddress, httpRequest);
        }
        if (user.isAccountLocked()) {
            return failLogin(submitted, user, "account_locked", ipAddress, httpRequest);
        }
        if (user.isAccountExpired()) {
            return failLogin(submitted, user, "account_expired", ipAddress, httpRequest);
        }

        // Migrated BCrypt hashes, and Argon2id hashes whose cost no longer matches configuration, are
        // upgraded here. This is the only moment the raw password is available to re-hash it.
        if (passwords.needsUpgrade(user.getPasswordHash())) {
            user.setPasswordHash(passwords.hash(request.password()));
            log.info("Upgraded the password hash for {} to Argon2id", user.getUsername());
        }

        throttle.recordSuccess(submitted);
        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        users.save(user);

        audit.success(SecurityAuditEvent.LOGIN_SUCCESS, user.getId(), user.getUsername(), httpRequest, Map.of());
        return issue(user, httpRequest);
    }

    /**
     * Exchanges a refresh token for a new pair.
     *
     * @param rawToken    the presented refresh token
     * @param httpRequest current request
     * @return a new token pair
     * @throws AuthenticationException when the token is unusable, in which case the client must sign in
     */
    public AuthResult refresh(String rawToken, HttpServletRequest httpRequest) {
        RefreshTokenService.RotationResult result = refreshTokens.rotate(
                rawToken, deviceInfo(httpRequest), clientAddress(httpRequest));

        switch (result.status()) {
            case REUSE_DETECTED -> {
                // The family is already revoked by the rotation service. Audited separately because this
                // is the one event here that indicates a possible theft rather than an expiry.
                audit.failure(SecurityAuditEvent.TOKEN_REUSE_DETECTED, result.userId(), null,
                        "refresh_token_reuse", httpRequest);
                throw AuthenticationException.invalidToken("refresh_token_reuse");
            }
            case EXPIRED -> throw AuthenticationException.invalidToken("refresh_token_expired");
            case INVALID -> throw AuthenticationException.invalidToken("refresh_token_unknown");
            case ROTATED -> {
                // Handled below.
            }
        }

        User user = users.findById(result.userId())
                .orElseThrow(() -> AuthenticationException.invalidToken("user_deleted"));

        // Re-checked on every refresh. Otherwise disabling an account would leave its holder able to keep
        // minting fresh access tokens for as long as the refresh token lived.
        if (!user.isUsable()) {
            refreshTokens.revokeAllForUser(user.getId(), "account no longer usable");
            audit.failure(SecurityAuditEvent.TOKEN_REFRESH, user.getId(), user.getUsername(),
                    "account_not_usable", httpRequest);
            throw AuthenticationException.invalidToken("account_not_usable");
        }

        audit.success(SecurityAuditEvent.TOKEN_REFRESH, user.getId(), user.getUsername(), httpRequest, Map.of());
        String accessToken = jwt.issueAccessToken(user);
        return new AuthResult(
                LoginResponse.of(accessToken, exposedToken(result.issued().rawToken()),
                        jwt.accessTokenTtlSeconds(), UserResponse.from(user)),
                result.issued().rawToken());
    }

    /**
     * Revokes a refresh token.
     *
     * <p>Never fails, even for an unknown token. Logout must be idempotent: a client whose token has already
     * expired still needs to be able to clear its state, and reporting an error would only tell a caller
     * whether some token they hold is still live.
     *
     * @param rawToken    the token to revoke, may be {@code null}
     * @param httpRequest current request
     */
    public void logout(String rawToken, HttpServletRequest httpRequest) {
        Optional<String> userId = refreshTokens.revoke(rawToken, "logout");
        userId.ifPresent(id -> users.findById(id).ifPresent(user ->
                audit.success(SecurityAuditEvent.LOGOUT, user.getId(), user.getUsername(), httpRequest, Map.of())));
    }

    /**
     * Changes the authenticated user's password.
     *
     * <p>Requires the current password even though the caller already holds a valid token. A stolen token
     * must not be upgradeable into permanent control of the account, and re-proving knowledge of the
     * password is what prevents that.
     *
     * <p>Every session is revoked afterwards, so a compromise really is ended by changing the password
     * rather than merely making future logins harder.
     *
     * @param userId      the authenticated user
     * @param request     current and new password
     * @param httpRequest current request
     * @throws AuthenticationException when the current password is wrong
     * @throws PasswordPolicyException when the new password is too weak
     */
    public void changePassword(String userId, ChangePasswordRequest request, HttpServletRequest httpRequest) {
        User user = users.findById(userId)
                .orElseThrow(() -> AuthenticationException.invalidCredentials("user_not_found"));

        if (!passwords.verify(request.currentPassword(), user.getPasswordHash())) {
            audit.failure(SecurityAuditEvent.PASSWORD_CHANGED, user.getId(), user.getUsername(),
                    "current_password_mismatch", httpRequest);
            throw AuthenticationException.invalidCredentials("current_password_mismatch");
        }
        if (!request.isConfirmed()) {
            throw new PasswordPolicyException(java.util.List.of("The confirmation does not match the new password"));
        }
        if (passwords.verify(request.newPassword(), user.getPasswordHash())) {
            throw new PasswordPolicyException(java.util.List.of("The new password must differ from the current one"));
        }

        user.setPasswordHash(passwords.hash(request.newPassword()));
        user.setCredentialsExpired(false);
        user.setUpdatedAt(Instant.now());
        users.save(user);

        int revoked = refreshTokens.revokeAllForUser(user.getId(), "password changed");
        audit.success(SecurityAuditEvent.PASSWORD_CHANGED, user.getId(), user.getUsername(), httpRequest,
                Map.of("sessionsRevoked", revoked));
        log.info("Password changed for {}; revoked {} session(s)", user.getUsername(), revoked);
    }

    /**
     * @param userId the authenticated user
     * @return their current profile
     */
    public UserResponse currentUser(String userId) {
        return users.findById(userId).map(UserResponse::from)
                .orElseThrow(() -> AuthenticationException.invalidToken("user_deleted"));
    }

    private AuthResult failLogin(String submitted, User user, String reason, String ipAddress,
                                 HttpServletRequest httpRequest) {
        throttle.recordFailure(submitted, ipAddress);
        audit.failure(SecurityAuditEvent.LOGIN_FAILURE,
                user == null ? null : user.getId(), submitted, reason, httpRequest);
        // The reason is recorded above and discarded here: every caller sees the same generic message.
        throw AuthenticationException.invalidCredentials(reason);
    }

    private AuthResult issue(User user, HttpServletRequest httpRequest) {
        String accessToken = jwt.issueAccessToken(user);
        RefreshTokenService.IssuedToken refresh = refreshTokens.issueForLogin(
                user, deviceInfo(httpRequest), clientAddress(httpRequest));
        return new AuthResult(
                LoginResponse.of(accessToken, exposedToken(refresh.rawToken()),
                        jwt.accessTokenTtlSeconds(), UserResponse.from(user)),
                refresh.rawToken());
    }

    /**
     * The refresh token as it should appear in the response body.
     *
     * <p>Null under the default cookie transport: including it in the body as well would defeat the point
     * of an HttpOnly cookie, since script could then read it from the login response.
     */
    private String exposedToken(String rawToken) {
        return properties.getJwt().isCookieTransport() ? null : rawToken;
    }

    private User save(User user) {
        try {
            return users.save(user);
        } catch (DuplicateKeyException ex) {
            // Two simultaneous registrations for the same name. The unique index is the real guarantee;
            // this turns its error into the same message the pre-check would have produced.
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("email")) {
                throw new DuplicateAccountException("email", "An account with that email already exists");
            }
            throw new DuplicateAccountException("username", "That username is already taken");
        }
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String clientAddress(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String deviceInfo(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }

    /**
     * A login or refresh result.
     *
     * <p>The raw refresh token is carried separately from the response body so the controller can decide
     * where it goes: into an HttpOnly cookie by default, or into the body when configured for a
     * non-browser client. The service does not know about cookies, and the controller does not know how
     * tokens are minted.
     *
     * @param response         the response body, whose {@code refreshToken} is null under cookie transport
     * @param rawRefreshToken  the token itself, for the controller to place
     */
    public record AuthResult(LoginResponse response, String rawRefreshToken) {
    }
}
