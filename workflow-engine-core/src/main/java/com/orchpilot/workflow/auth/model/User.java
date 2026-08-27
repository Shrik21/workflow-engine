package com.orchpilot.workflow.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A platform user.
 *
 * <p>The field is named {@code passwordHash}, not {@code password} and not {@code encryptedPassword},
 * because the name is part of the contract: this document holds a one-way Argon2id digest and the
 * application has no code path that can reverse it. There is deliberately no getter that returns
 * anything decryptable, and no REST response type in this code base carries this field.
 *
 * <p>A mutable persistence model rather than a record: Spring Data Mongo needs a no-argument
 * constructor and setters to materialise it, and the write paths are all inside
 * {@code UserAdminService} and {@code AuthenticationService}, which is where the invariants are
 * enforced.
 */
@Document(collection = "users")
public class User {

    @Id
    private String id;

    /** Login name. Unique, case-insensitively, which is enforced by storing it already lower-cased. */
    @Indexed(unique = true)
    private String username;

    @Indexed(unique = true)
    private String email;

    /**
     * Argon2id digest, in the self-describing PHC format that begins {@code $argon2id$}.
     *
     * <p>BCrypt hashes beginning {@code $2a$} are also accepted on verification so a migrated
     * database keeps working; they are re-hashed to Argon2id on the next successful login.
     */
    @Field("passwordHash")
    private String passwordHash;

    private String firstName;
    private String lastName;

    /** Never empty in practice: registration assigns {@link Role#USER}. */
    private Set<Role> roles = new LinkedHashSet<>();

    /** An administrator can disable an account without deleting it, preserving its audit history. */
    private boolean enabled = true;

    /**
     * Administrative lock, set by a person and cleared by a person.
     *
     * <p>Distinct from the temporary lockout the brute-force throttle applies. Conflating them would
     * let an attacker permanently lock a real user out by guessing passwords at them.
     */
    private boolean accountLocked;

    private boolean accountExpired;
    private boolean credentialsExpired;

    /**
     * Reserved for multi-tenancy. Null today.
     *
     * <p>Present now because retrofitting a tenant discriminator onto an authorization model is far
     * harder than carrying an unused nullable field: every query and every ownership check would have
     * to be revisited. {@code WorkflowAccessPolicy} is the single place that would learn to use it.
     */
    @Indexed
    private String tenantId;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;

    /** Who created this account: an admin's user id, or {@code "self-registration"}. */
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

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(roles);
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

    public boolean isAccountExpired() {
        return accountExpired;
    }

    public void setAccountExpired(boolean accountExpired) {
        this.accountExpired = accountExpired;
    }

    public boolean isCredentialsExpired() {
        return credentialsExpired;
    }

    public void setCredentialsExpired(boolean credentialsExpired) {
        this.credentialsExpired = credentialsExpired;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
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
     * @return every permission this user's roles grant, computed rather than stored
     */
    public Set<Permission> permissions() {
        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        for (Role role : roles) {
            granted.addAll(role.permissions());
        }
        return granted;
    }

    /**
     * @param permission the permission to test
     * @return whether any of this user's roles grants it
     */
    public boolean hasPermission(Permission permission) {
        return roles.stream().anyMatch(role -> role.grants(permission));
    }

    /**
     * @return whether the account may authenticate at all, ignoring the password
     */
    public boolean isUsable() {
        return enabled && !accountLocked && !accountExpired;
    }

    /**
     * Deliberately omits the password hash so that logging a user cannot leak it, even accidentally
     * through a debugger or a string-concatenated log line.
     */
    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username + ", roles=" + roles + ", enabled=" + enabled + "}";
    }
}
