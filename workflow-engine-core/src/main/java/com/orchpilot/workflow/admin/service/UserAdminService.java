package com.orchpilot.workflow.admin.service;

import com.orchpilot.workflow.admin.dto.AdminUserRequests;
import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditService;
import com.orchpilot.workflow.auth.dto.UserResponse;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.auth.service.DuplicateAccountException;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.auth.service.PasswordService;
import com.orchpilot.workflow.auth.service.RefreshTokenService;
import com.orchpilot.workflow.exception.WorkflowNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * User administration.
 *
 * <p>Two invariants are enforced here rather than left to the caller, because both are ways an
 * administrator can lock everybody out of the platform with one request:
 *
 * <ul>
 *   <li><b>The last administrator cannot be removed.</b> Deleting, disabling or demoting the only remaining
 *       ADMIN is refused. Recovering from it would mean editing MongoDB by hand.</li>
 *   <li><b>An administrator cannot disable, lock, demote or delete themselves.</b> Not because it is unsafe
 *       in itself, but because it is almost always a mistake, and the message that explains it is more
 *       useful than the lockout that follows.</li>
 * </ul>
 *
 * <p>Every state change that invalidates a session revokes that user's refresh tokens. Disabling an account
 * while leaving its holder able to keep minting access tokens for a week would make the button meaningless.
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository users;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final SecurityAuditService audit;

    public UserAdminService(UserRepository users, PasswordService passwords,
                            RefreshTokenService refreshTokens, SecurityAuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
    }

    /**
     * @param search   optional term matched against username, email and name
     * @param role     optional role filter
     * @param pageable page request
     * @return a page of users, without password hashes
     */
    public Page<UserResponse> list(String search, Role role, Pageable pageable) {
        Page<User> page;
        if (search != null && !search.isBlank()) {
            // Quoted so a term containing regular-expression metacharacters is matched literally rather
            // than becoming a pattern, which would otherwise allow a crafted search to scan the whole
            // collection or error out.
            page = users.search(Pattern.quote(search.trim()), pageable);
        } else if (role != null) {
            page = users.findByRolesContaining(role, pageable);
        } else {
            page = users.findAll(pageable);
        }
        return page.map(UserResponse::from);
    }

    /**
     * @param userId the user
     * @return their profile
     */
    public UserResponse get(String userId) {
        return UserResponse.from(require(userId));
    }

    /**
     * Creates an account.
     *
     * @param request     the account to create
     * @param httpRequest current request, for audit context
     * @return the created user
     */
    public UserResponse create(AdminUserRequests.CreateUser request, HttpServletRequest httpRequest) {
        String username = normalise(request.username());
        String email = normalise(request.email());

        if (users.existsByUsername(username)) {
            throw new DuplicateAccountException("username", "That username is already taken");
        }
        if (users.existsByEmail(email)) {
            throw new DuplicateAccountException("email", "An account with that email already exists");
        }

        Set<Role> roles = request.roles() == null || request.roles().isEmpty()
                ? Set.of(Role.USER)
                : Set.copyOf(request.roles());

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwords.hash(request.password()));
        user.setFirstName(trimToNull(request.firstName()));
        user.setLastName(trimToNull(request.lastName()));
        user.setRoles(roles);
        user.setEnabled(request.enabled() == null || request.enabled());
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setCreatedBy(CurrentUser.userId().orElse("system"));

        User saved = users.save(user);
        auditChange(SecurityAuditEvent.USER_CREATED, saved, httpRequest,
                Map.of("roles", names(roles), "enabled", saved.isEnabled()));
        log.info("Created user {} with roles {}", saved.getUsername(), roles);
        return UserResponse.from(saved);
    }

    /**
     * Updates a profile.
     *
     * @param userId      the user
     * @param request     new values; null fields are left unchanged
     * @param httpRequest current request
     * @return the updated user
     */
    public UserResponse update(String userId, AdminUserRequests.UpdateUser request,
                               HttpServletRequest httpRequest) {
        User user = require(userId);

        if (request.email() != null && !request.email().isBlank()) {
            String email = normalise(request.email());
            if (!email.equals(user.getEmail()) && users.existsByEmail(email)) {
                throw new DuplicateAccountException("email", "An account with that email already exists");
            }
            user.setEmail(email);
        }
        if (request.firstName() != null) {
            user.setFirstName(trimToNull(request.firstName()));
        }
        if (request.lastName() != null) {
            user.setLastName(trimToNull(request.lastName()));
        }
        user.setUpdatedAt(Instant.now());

        User saved = users.save(user);
        auditChange(SecurityAuditEvent.USER_UPDATED, saved, httpRequest, Map.of());
        return UserResponse.from(saved);
    }

    /**
     * Replaces a user's roles.
     *
     * <p>Sessions are revoked so the new authorities take effect on the next sign-in rather than whenever
     * the current refresh token happens to expire. Access tokens are already re-checked against the database
     * on every request, so a demotion takes effect immediately either way; revoking makes it unambiguous.
     *
     * @param userId      the user
     * @param roles       the complete new role set
     * @param httpRequest current request
     * @return the updated user
     */
    public UserResponse updateRoles(String userId, Set<Role> roles, HttpServletRequest httpRequest) {
        User user = require(userId);
        Set<Role> previous = new LinkedHashSet<>(user.getRoles());
        Set<Role> next = Set.copyOf(roles);

        if (previous.contains(Role.ADMIN) && !next.contains(Role.ADMIN)) {
            forbidSelf(user, "remove your own administrator role");
            requireAnotherAdmin(user, "demote");
        }

        user.setRoles(next);
        user.setUpdatedAt(Instant.now());
        User saved = users.save(user);

        int revoked = refreshTokens.revokeAllForUser(saved.getId(), "roles changed");
        auditChange(SecurityAuditEvent.ROLE_CHANGED, saved, httpRequest,
                Map.of("from", names(previous), "to", names(next), "sessionsRevoked", revoked));
        log.info("Changed roles for {} from {} to {}", saved.getUsername(), previous, next);
        return UserResponse.from(saved);
    }

    /**
     * Enables or disables an account.
     *
     * @param userId      the user
     * @param request     the new status
     * @param httpRequest current request
     * @return the updated user
     */
    public UserResponse updateStatus(String userId, AdminUserRequests.UpdateStatus request,
                                     HttpServletRequest httpRequest) {
        User user = require(userId);
        if (!request.enabled()) {
            forbidSelf(user, "disable your own account");
            if (user.getRoles().contains(Role.ADMIN)) {
                requireAnotherAdmin(user, "disable");
            }
        }

        user.setEnabled(request.enabled());
        user.setUpdatedAt(Instant.now());
        User saved = users.save(user);

        int revoked = request.enabled() ? 0 : refreshTokens.revokeAllForUser(saved.getId(), "account disabled");
        auditChange(request.enabled() ? SecurityAuditEvent.USER_ENABLED : SecurityAuditEvent.USER_DISABLED,
                saved, httpRequest, Map.of("reason", nullSafe(request.reason()), "sessionsRevoked", revoked));
        return UserResponse.from(saved);
    }

    /**
     * Locks or unlocks an account.
     *
     * <p>This is the administrative lock, entirely separate from the temporary lockout the brute-force
     * throttle applies. Keeping them apart means an attacker guessing passwords cannot produce a state only
     * an administrator can clear.
     *
     * @param userId      the user
     * @param locked      whether to lock
     * @param httpRequest current request
     * @return the updated user
     */
    public UserResponse setLocked(String userId, boolean locked, HttpServletRequest httpRequest) {
        User user = require(userId);
        if (locked) {
            forbidSelf(user, "lock your own account");
            if (user.getRoles().contains(Role.ADMIN)) {
                requireAnotherAdmin(user, "lock");
            }
        }

        user.setAccountLocked(locked);
        user.setUpdatedAt(Instant.now());
        User saved = users.save(user);

        int revoked = locked ? refreshTokens.revokeAllForUser(saved.getId(), "account locked") : 0;
        auditChange(locked ? SecurityAuditEvent.USER_LOCKED : SecurityAuditEvent.USER_UNLOCKED,
                saved, httpRequest, Map.of("sessionsRevoked", revoked));
        return UserResponse.from(saved);
    }

    /**
     * Deletes an account.
     *
     * <p>The audit trail keeps its record of what the account did; only the account itself goes. Disabling is
     * usually the better choice and the console says so, but deletion has to exist for a mistaken or test
     * account.
     *
     * @param userId      the user
     * @param httpRequest current request
     */
    public void delete(String userId, HttpServletRequest httpRequest) {
        User user = require(userId);
        forbidSelf(user, "delete your own account");
        if (user.getRoles().contains(Role.ADMIN)) {
            requireAnotherAdmin(user, "delete");
        }

        refreshTokens.revokeAllForUser(user.getId(), "account deleted");
        users.delete(user);
        auditChange(SecurityAuditEvent.USER_DELETED, user, httpRequest, Map.of());
        log.info("Deleted user {} ({})", user.getUsername(), user.getId());
    }

    /**
     * @param userId the user
     * @return how many live sessions they have, so an administrator can see current access
     */
    public long liveSessionCount(String userId) {
        return refreshTokens.liveSessions(userId).size();
    }

    private User require(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new WorkflowNotFoundException("No user with id '" + userId + "'"));
    }

    /** Refuses an action that would leave the platform with no usable administrator. */
    private void requireAnotherAdmin(User subject, String verb) {
        long otherAdmins = users.findAll().stream()
                .filter(candidate -> !candidate.getId().equals(subject.getId()))
                .filter(candidate -> candidate.getRoles().contains(Role.ADMIN))
                .filter(User::isUsable)
                .count();
        if (otherAdmins == 0) {
            throw OperationNotAllowedException.conflict("Cannot " + verb
                    + " the last administrator. Grant ADMIN to another account first, otherwise nobody "
                    + "would be able to administer the platform.");
        }
    }

    private void forbidSelf(User subject, String action) {
        String actingId = CurrentUser.userId().orElse(null);
        if (actingId != null && actingId.equals(subject.getId())) {
            throw OperationNotAllowedException.conflict(
                    "You cannot " + action + ". Ask another administrator to do it.");
        }
    }

    private void auditChange(SecurityAuditEvent event, User subject, HttpServletRequest request,
                             Map<String, Object> details) {
        AuthPrincipal actor = CurrentUser.principal().orElse(null);
        audit.administrative(event,
                actor == null ? null : actor.getUserId(),
                actor == null ? "system" : actor.getUsername(),
                subject.getId(), subject.getUsername(), request, details);
    }

    private static Set<String> names(Set<Role> roles) {
        Set<String> result = new LinkedHashSet<>();
        roles.forEach(role -> result.add(role.name()));
        return result;
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

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
