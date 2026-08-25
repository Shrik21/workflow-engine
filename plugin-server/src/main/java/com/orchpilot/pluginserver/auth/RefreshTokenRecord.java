package com.orchpilot.pluginserver.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A refresh token, stored as a hash.
 *
 * <h2>Why the token itself is not here</h2>
 *
 * A refresh token is a bearer credential with a week of life in it. Storing it verbatim would mean anybody who
 * can read this collection — a backup, a misconfigured export, an operator with a shell — can sign in as any
 * user without a password and without leaving a failed attempt behind. The hash is enough to recognise a token
 * presented back, and not enough to produce one.
 *
 * <p>SHA-256 rather than a password hash: this value is 256 bits of randomness from a secure generator, not a
 * human-chosen secret, so there is nothing to brute-force and no reason to make verification deliberately slow
 * on a path that runs on every token refresh.
 *
 * <h2>Rotation leaves a trail</h2>
 *
 * Refreshing consumes the presented token and issues a new one, with {@link #replacedBy} linking the two. That
 * chain is what makes reuse detectable: a token that has already been rotated turning up again means either a
 * copy is in circulation or a client is retrying badly, and the safe response is to revoke the whole chain.
 */
@Document(collection = "refresh_tokens")
public class RefreshTokenRecord {

    @Id
    private String id;

    /** SHA-256 of the token, hex. Indexed because every refresh is a lookup by this value. */
    @Indexed(unique = true)
    private String tokenHash;

    @Indexed
    private String userId;

    private String username;

    /**
     * When it stops being accepted.
     *
     * <p>Indexed with a TTL so expired records are removed by MongoDB rather than accumulating for as long as
     * the registry runs. Expiry is still checked in code: a TTL monitor runs about once a minute, so a record
     * may outlive its expiry briefly and must not be honoured in that window.
     */
    @Indexed(expireAfter = "P0D")
    private Instant expiresAt;

    private boolean revoked;
    private Instant revokedAt;
    private String revokedReason;

    /** The token issued when this one was rotated. Null until it is used. */
    private String replacedBy;

    private Instant createdAt;
    private Instant lastUsedAt;

    /** Recorded so a person can recognise their own sessions. Never used for an authorisation decision. */
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
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

    public String getReplacedBy() {
        return replacedBy;
    }

    public void setReplacedBy(String replacedBy) {
        this.replacedBy = replacedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
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

    /** @return whether this token may still be exchanged */
    public boolean isUsable() {
        return !revoked && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /** @return whether this token has already been rotated, which makes a second presentation suspicious */
    public boolean isAlreadyRotated() {
        return replacedBy != null;
    }
}
