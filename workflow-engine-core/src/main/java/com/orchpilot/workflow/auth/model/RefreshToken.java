package com.orchpilot.workflow.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A refresh token, stored as a hash.
 *
 * <p>The raw token is a 256-bit random value handed to the client once and never persisted. What is
 * stored is its SHA-256 digest, for the same reason passwords are hashed: a dump of this collection
 * gives an attacker nothing they can present. Lookup is by digest, so verification needs no reverse
 * operation.
 *
 * <p>SHA-256 rather than Argon2id here, unlike passwords, and the difference is deliberate. A
 * password is low-entropy and guessable, so it needs a deliberately slow hash. This token is 256 bits
 * of cryptographic randomness, so brute force is not a threat and a fast digest is correct; using
 * Argon2id would add tens of milliseconds to every token refresh for no security gain.
 *
 * <p>{@code familyId} ties a rotated chain together. When a revoked token is presented, which means
 * either a replay or a theft, the whole family is revoked rather than just that token.
 */
@Document(collection = "refresh_tokens")
public class RefreshToken {

    @Id
    private String id;

    @Indexed(unique = true)
    private String tokenHash;

    @Indexed
    private String userId;

    /**
     * Groups every token descended from one login, so a detected replay can revoke the whole chain.
     */
    @Indexed
    private String familyId;

    /**
     * Expiry. A TTL index on this field lets MongoDB reclaim expired documents, so no cleanup job is
     * needed and the collection cannot grow without bound.
     */
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    private boolean revoked;
    private Instant revokedAt;
    private String revokedReason;

    /** Id of the token issued when this one was rotated, for tracing a chain. */
    private String replacedByTokenId;

    private Instant createdAt;

    /** Coarse client description from the User-Agent, so a user can recognise their own sessions. */
    private String deviceInfo;

    private String ipAddress;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFamilyId() {
        return familyId;
    }

    public void setFamilyId(String familyId) {
        this.familyId = familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(String revokedReason) {
        this.revokedReason = revokedReason;
    }

    public String getReplacedByTokenId() {
        return replacedByTokenId;
    }

    public void setReplacedByTokenId(String replacedByTokenId) {
        this.replacedByTokenId = replacedByTokenId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDeviceInfo() {
        return deviceInfo;
    }

    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = deviceInfo;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    /**
     * @param now current time
     * @return whether this token may still be exchanged
     */
    public boolean isUsable(Instant now) {
        return !revoked && expiresAt != null && expiresAt.isAfter(now);
    }

    /** Omits the hash: it is not a secret, but there is no reason for it to appear in a log. */
    @Override
    public String toString() {
        return "RefreshToken{id=" + id + ", userId=" + userId + ", revoked=" + revoked + "}";
    }
}
