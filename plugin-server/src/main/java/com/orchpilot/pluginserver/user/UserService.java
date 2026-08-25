package com.orchpilot.pluginserver.user;

import com.orchpilot.pluginserver.auth.RefreshTokenService;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.role.Role;
import com.orchpilot.pluginserver.role.RoleService;
import com.orchpilot.pluginserver.security.AuthProperties;
import com.orchpilot.pluginserver.security.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Accounts: creating them, changing them, and the bookkeeping around failed sign-ins.
 *
 * <h2>Passwords in, never out</h2>
 *
 * A raw password reaches this class in exactly three places — creation, reset, and change — and in each it is
 * hashed and forgotten within the same method. Nothing here returns one, stores one, or logs one.
 *
 * <h2>Locking is timed</h2>
 *
 * Repeated failures lock an account for a configured interval rather than indefinitely. A permanent lock turns
 * a mistyped password into a support ticket, and hands anybody who knows a username a way to disable that
 * account at will. The lock lifts by the clock, and a successful sign-in clears the counter.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final RoleService roles;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthProperties properties;
    private final RefreshTokenService refreshTokens;

    public UserService(UserRepository users, RoleService roles, PasswordEncoder passwordEncoder,
                       PasswordPolicy passwordPolicy, AuthProperties properties,
                       RefreshTokenService refreshTokens) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.properties = properties;
        this.refreshTokens = refreshTokens;
    }

    // ------------------------------------------------------------------- reads

    public List<User> findAll() {
        return users.findAllByOrderByUsernameAsc();
    }

    public Optional<User> findByUsername(String username) {
        return users.findByUsernameIgnoreCase(username);
    }

    public User require(String id) {
        return users.findById(id)
                .orElseThrow(() -> PluginServerException.notFound("USER_NOT_FOUND",
                        "There is no account with that id."));
    }

    /** @return whether any account exists at all, which decides whether the registry bootstraps one */
    public boolean isEmpty() {
        return users.count() == 0;
    }

    // ----------------------------------------------------------------- writing

    /**
     * Creates an account.
     *
     * @param request  what to create
     * @param actor    who is creating it, for the record
     * @return the stored account
     */
    public User create(NewUser request, String actor) {
        String username = normaliseUsername(request.username());
        String email = normaliseEmail(request.email());

        if (users.existsByUsernameIgnoreCase(username)) {
            throw PluginServerException.conflict("USERNAME_TAKEN", "That username is already in use.");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw PluginServerException.conflict("EMAIL_TAKEN", "That email address is already in use.");
        }
        requireAcceptablePassword(request.password(), username);

        Set<String> assigned = resolveRoles(request.roles());

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRoles(assigned);
        user.setServiceAccount(request.serviceAccount());
        user.setEnabled(true);
        user.setMustChangePassword(request.mustChangePassword());
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setCredentialsChangedAt(Instant.now());
        user.setCreatedBy(actor);

        User saved = users.save(user);
        log.info("Created account '{}' with roles {} (by {})", saved.getUsername(), saved.getRoles(), actor);
        return saved;
    }

    /**
     * Updates the mutable parts of an account. Not the password, which has its own paths.
     *
     * @param id      which account
     * @param request the new values
     * @return the stored account
     */
    public User update(String id, UpdateUser request) {
        User user = require(id);

        if (request.email() != null && !request.email().isBlank()) {
            String email = normaliseEmail(request.email());
            if (!email.equalsIgnoreCase(user.getEmail()) && users.existsByEmailIgnoreCase(email)) {
                throw PluginServerException.conflict("EMAIL_TAKEN", "That email address is already in use.");
            }
            user.setEmail(email);
        }
        if (request.firstName() != null) {
            user.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName());
        }
        if (request.roles() != null) {
            Set<String> assigned = resolveRoles(request.roles());
            refuseRemovingTheLastAdministrator(user, assigned);
            user.setRoles(assigned);
        }
        user.setUpdatedAt(Instant.now());
        return users.save(user);
    }

    /**
     * Enables or disables an account.
     *
     * <p>Disabling revokes every session it holds. Leaving them alive would mean a disabled account keeps
     * working for as long as its access token lasts and can keep renewing it, which is not what anybody means
     * by disabled.
     *
     * @param id      which account
     * @param enabled the new state
     * @return the stored account
     */
    public User setEnabled(String id, boolean enabled) {
        User user = require(id);
        if (!enabled) {
            refuseRemovingTheLastAdministrator(user, Set.of());
        }
        user.setEnabled(enabled);
        user.setUpdatedAt(Instant.now());
        User saved = users.save(user);
        if (!enabled) {
            int revoked = refreshTokens.revokeAllFor(id, "account disabled");
            log.info("Disabled account '{}' and revoked {} session(s)", user.getUsername(), revoked);
        }
        return saved;
    }

    /**
     * Sets a new password on behalf of an administrator.
     *
     * <p>Every existing session is revoked and the account must change the password at next sign-in: whoever
     * set it has seen it, so it is a one-time credential rather than a password.
     *
     * @param id          which account
     * @param newPassword the temporary password
     * @return the stored account
     */
    public User resetPassword(String id, String newPassword) {
        User user = require(id);
        requireAcceptablePassword(newPassword, user.getUsername());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setCredentialsChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setLockedUntil(null);
        User saved = users.save(user);

        refreshTokens.revokeAllFor(id, "password reset by an administrator");
        log.info("Reset the password of '{}' and revoked its sessions", user.getUsername());
        return saved;
    }

    /**
     * Changes a password on the account holder's own request.
     *
     * @param user            the account
     * @param currentPassword proof it is them, required even though they are already authenticated: a stolen
     *                        access token must not be convertible into permanent control of the account
     * @param newPassword     the replacement
     */
    public void changePassword(User user, String currentPassword, String newPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw PluginServerException.badRequest("PASSWORD_INCORRECT",
                    "The current password is not correct.");
        }
        requireAcceptablePassword(newPassword, user.getUsername());
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw PluginServerException.badRequest("PASSWORD_UNCHANGED",
                    "The new password must be different from the current one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setCredentialsChangedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        users.save(user);

        // Every other session ends. A password change is usually a response to believing somebody else has it.
        refreshTokens.revokeAllFor(user.getId(), "password changed");
        log.info("Password changed for '{}'; all sessions revoked", user.getUsername());
    }

    /**
     * Deletes an account.
     *
     * <p>Offered, but disabling is the better answer and the UI says so: a deleted account leaves audit rows
     * pointing at a name nobody can look up.
     *
     * @param id which account
     */
    public void delete(String id) {
        User user = require(id);
        refuseRemovingTheLastAdministrator(user, Set.of());
        refreshTokens.revokeAllFor(id, "account deleted");
        users.delete(user);
        log.info("Deleted account '{}'", user.getUsername());
    }

    // --------------------------------------------------------- sign-in support

    /** Records a successful sign-in and clears any failure state. */
    public void recordSuccessfulLogin(User user) {
        user.setLastLoginAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setAccountLocked(false);
        user.setLockedUntil(null);
        users.save(user);
    }

    /**
     * Records a failed sign-in, locking the account once the threshold is reached.
     *
     * @param user the account the attempt named
     * @return whether this failure locked it
     */
    public boolean recordFailedLogin(User user) {
        AuthProperties.Login policy = properties.getLogin();
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);

        boolean locked = attempts >= policy.getMaxFailedAttempts();
        if (locked) {
            user.setAccountLocked(true);
            user.setLockedUntil(Instant.now().plus(policy.getLockDuration()));
            log.warn("Account '{}' locked after {} failed attempts; it unlocks at {}", user.getUsername(),
                    attempts, user.getLockedUntil());
        }
        users.save(user);
        return locked;
    }

    /**
     * Clears an expired lock.
     *
     * @param user the account
     * @return whether the account is currently locked after this check
     */
    public boolean settleLock(User user) {
        if (!user.isAccountLocked()) {
            return false;
        }
        if (user.getLockedUntil() != null && !user.getLockedUntil().isAfter(Instant.now())) {
            user.setAccountLocked(false);
            user.setLockedUntil(null);
            user.setFailedLoginAttempts(0);
            users.save(user);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    /** Every violated rule at once, so somebody learns all of them in one attempt. */
    private void requireAcceptablePassword(String password, String username) {
        List<String> problems = passwordPolicy.violations(password, username);
        if (!problems.isEmpty()) {
            throw PluginServerException.invalid("PASSWORD_WEAK",
                    "The password does not meet this registry's policy.", problems);
        }
    }

    private Set<String> resolveRoles(Set<String> requested) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (requested == null || requested.isEmpty()) {
            throw PluginServerException.badRequest("ROLES_REQUIRED",
                    "An account must have at least one role.");
        }
        for (String name : requested) {
            // require() throws when the role does not exist, which is what stops an account being created
            // against a typo and silently having no access.
            resolved.add(roles.require(name.trim().toUpperCase(java.util.Locale.ROOT)).getName());
        }
        return resolved;
    }

    /**
     * Refuses a change that would leave the registry with no way in.
     *
     * <p>Removing the administrator role from the only enabled administrator, or disabling or deleting them,
     * locks everybody out of a running registry with no recovery short of editing the database by hand.
     */
    private void refuseRemovingTheLastAdministrator(User user, Set<String> newRoles) {
        boolean wasAdmin = user.getRoles().contains(Role.PLUGIN_ADMIN);
        boolean staysAdmin = newRoles.contains(Role.PLUGIN_ADMIN);
        if (!wasAdmin || staysAdmin) {
            return;
        }
        long remaining = users.countByRolesContainingAndEnabledTrue(Role.PLUGIN_ADMIN);
        if (remaining <= 1) {
            throw PluginServerException.conflict("LAST_ADMINISTRATOR",
                    "This is the only enabled administrator. Give another account the "
                            + Role.PLUGIN_ADMIN + " role first, or nobody will be able to administer this "
                            + "registry.");
        }
    }

    private static String normaliseUsername(String username) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._-]{3,64}")) {
            throw PluginServerException.badRequest("USERNAME_INVALID",
                    "A username must be 3 to 64 characters of letters, digits, dots, dashes or underscores.");
        }
        return username.trim();
    }

    private static String normaliseEmail(String email) {
        if (email == null || !email.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw PluginServerException.badRequest("EMAIL_INVALID", "A valid email address is required.");
        }
        return email.trim();
    }

    /**
     * What an administrator supplies to create an account.
     *
     * @param username          unique
     * @param email             unique
     * @param firstName         optional
     * @param lastName          optional
     * @param password          the initial password, hashed immediately
     * @param roles             at least one, each of which must exist
     * @param serviceAccount    whether this is a machine identity
     * @param mustChangePassword whether the holder must replace the password before doing anything
     */
    public record NewUser(String username, String email, String firstName, String lastName, String password,
                          Set<String> roles, boolean serviceAccount, boolean mustChangePassword) {
    }

    /**
     * What may be changed on an existing account. Null means "leave alone".
     *
     * @param email     new address
     * @param firstName new given name
     * @param lastName  new family name
     * @param roles     the complete new set of roles
     */
    public record UpdateUser(String email, String firstName, String lastName, Set<String> roles) {
    }
}
