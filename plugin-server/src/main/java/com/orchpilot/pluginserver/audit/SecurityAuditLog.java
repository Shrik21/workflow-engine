package com.orchpilot.pluginserver.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One security-relevant thing that happened.
 *
 * <h2>Failures are the point</h2>
 *
 * A trail of successful sign-ins tells you very little. The rows worth having are the refused ones: a password
 * tried five times against one account, a token presented after it was rotated, a viewer repeatedly reaching
 * for the upload endpoint. Every one of those is recorded here with the same weight as a success, because the
 * pattern is the finding.
 *
 * <h2>Nothing sensitive lands in a row</h2>
 *
 * No passwords, no tokens, no hashes, not even a truncated one. {@link #details} carries field names and
 * decisions — which permission was missing, how many attempts preceded a lock — never values. An audit trail
 * that leaks the credentials it is describing has made the problem worse.
 */
@Document(collection = "security_audit_logs")
public class SecurityAuditLog {

    /** What happened. Deliberately a closed set: a searchable trail needs a stable vocabulary. */
    public enum Action {
        LOGIN_SUCCESS,
        LOGIN_FAILURE,
        LOGIN_BLOCKED,
        LOGOUT,
        TOKEN_REFRESH,
        TOKEN_REUSE_DETECTED,
        PASSWORD_CHANGED,
        PASSWORD_RESET,
        ACCOUNT_LOCKED,
        ACCOUNT_UNLOCKED,
        USER_CREATED,
        USER_UPDATED,
        USER_ENABLED,
        USER_DISABLED,
        USER_DELETED,
        ROLE_CREATED,
        ROLE_UPDATED,
        ROLE_DELETED,
        PERMISSION_DENIED
    }

    @Id
    private String id;

    /** Null for a failed sign-in against a username that does not exist. */
    private String userId;

    /** Recorded even when no account matched, because the attempted name is itself the finding. */
    private String username;

    private Action action;

    /** What kind of thing was acted on: {@code USER}, {@code ROLE}, {@code PLUGIN}, {@code SESSION}. */
    private String resource;

    private String resourceId;

    @Indexed
    private Instant timestamp;

    private String ipAddress;
    private String userAgent;

    private boolean success;

    /** Field names and decisions. Never values, and never anything derived from a credential. */
    private Map<String, Object> details = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    }
}
