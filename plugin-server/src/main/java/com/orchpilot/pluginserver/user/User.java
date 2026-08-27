package com.orchpilot.pluginserver.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An account on this registry.
 *
 * <h2>Independent of the workflow platform</h2>
 *
 * These accounts belong to the registry alone. A person may exist in both this collection and the workflow
 * platform's, with the same name and a different password, and neither knows about the other. That is the
 * point: the registry distributes executable code to every engine in the estate, and who may publish to it is
 * a decision it must be able to make without asking another service.
 *
 * <h2>The password field holds a hash and nothing else</h2>
 *
 * One-way, always. There is no method anywhere in this service that turns the stored value back into a
 * password, because no such method can exist for a correctly hashed value — and the absence is deliberate
 * rather than an oversight. Verification compares a candidate against the hash; it never recovers the
 * original.
 *
 * <h2>Permissions come from roles</h2>
 *
 * A user holds role names. What those roles permit is resolved at authentication time, so changing a role
 * changes what every holder can do without touching a single account.
 */
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    @Indexed(unique = true)
    private String email;

    /**
     * The password hash, prefixed with the algorithm that produced it, e.g. {@code {argon2}$argon2id$...}.
     *
     * <p>Never returned by any endpoint and never logged. The prefix is what allows a hash written by an older
     * algorithm to keep verifying while new ones are written with the current one.
     */
    private String passwordHash;

    private String firstName;
    private String lastName;

    /** Role names. What they grant is resolved from the roles collection at sign-in. */
    private Set<String> roles = new LinkedHashSet<>();

    /**
     * Permissions granted to this account directly, on top of its roles.
     *
     * <p>Normally empty, and deliberately so: an account whose access is spelled out individually is one
     * nobody can reason about by looking at the roles. It exists for the case a role does not fit and
     * inventing a role for one person would be worse.
     */
    private Set<String> directPermissions = new LinkedHashSet<>();

    /** A machine account. Excluded from interactive sign-in, and never given administrative roles. */
    private boolean serviceAccount;

    private boolean enabled = true;

    /**
     * Set when the account was locked by repeated failed sign-ins.
     *
     * <p>Paired with {@link #lockedUntil} rather than standing alone, because a lock that only an
     * administrator can lift turns a forgotten password into a support ticket and an attacker's guessing into
     * a denial of service against the real user.
     */
    private boolean accountLocked;

    private Instant lockedUntil;
    private int failedLoginAttempts;

    /**
     * Forces a password change before anything else is allowed.
     *
     * <p>Set on the bootstrap administrator, whose password arrives from configuration and has therefore been
     * seen by whatever wrote that configuration.
     */
    private boolean mustChangePassword;

    /**
     * When credentials last changed.
     *
     * <p>Every refresh token issued before this instant is treated as dead. That is what makes a password
     * change end other sessions without hunting for their tokens.
     */
    private Instant credentialsChangedAt;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;
    private String createdBy;

    @Version
    private Long documentVersion;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(roles);
    }

    public Set<String> getDirectPermissions() {
        return directPermissions;
    }

    public void setDirectPermissions(Set<String> directPermissions) {
        this.directPermissions =
                directPermissions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(directPermissions);
    }

    public boolean isServiceAccount() {
        return serviceAccount;
    }

    public void setServiceAccount(boolean serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAccountLocked() {
        return accountLocked;
    }

    public void setAccountLocked(boolean accountLocked) {
        this.accountLocked = accountLocked;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Instant getCredentialsChangedAt() {
        return credentialsChangedAt;
    }

    public void setCredentialsChangedAt(Instant credentialsChangedAt) {
        this.credentialsChangedAt = credentialsChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    /**
     * Whether the lock is currently in force.
     *
     * <p>Reads the clock rather than a flag: a lock with an expiry that has passed is no lock, and requiring a
     * scheduled job to clear it would mean an account stays locked for as long as that job is broken.
     *
     * @return whether sign-in must be refused
     */
    public boolean isCurrentlyLocked() {
        if (!accountLocked) {
            return false;
        }
        return lockedUntil == null || lockedUntil.isAfter(Instant.now());
    }

    /** @return the name to show, falling back to the username when no real name was recorded */
    public String displayName() {
        String full = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return full.isEmpty() ? username : full;
    }

    /** @return a description safe to log: never the hash, and never anything derived from it */
    @Override
    public String toString() {
        return "User{" + username + ", roles=" + roles + ", enabled=" + enabled + "}";
    }
}
