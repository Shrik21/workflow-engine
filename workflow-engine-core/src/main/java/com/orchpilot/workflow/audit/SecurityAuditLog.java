package com.orchpilot.workflow.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * One security audit record.
 *
 * <p>Note what this document cannot hold. There is no password field, no token field and no free-text
 * message parameter on the writing API. {@code details} exists for structured context such as which
 * roles changed, and the writer strips any key whose name suggests a credential. Making it impossible
 * to log a secret is more reliable than remembering not to.
 */
@Document(collection = "security_audit_logs")
public class SecurityAuditLog {

    @Id
    private String id;

    @Indexed
    private SecurityAuditEvent event;

    /** Subject of the event: the account it happened to. Null when the username was not resolvable. */
    @Indexed
    private String userId;

    /**
     * Username as supplied by the caller.
     *
     * <p>Recorded even for a failed login against a non-existent account, because knowing which names
     * are being tried is the point of an audit trail. The login response still does not reveal whether
     * the account exists.
     */
    private String username;

    /** Who performed the action, when different from the subject, such as an admin changing a role. */
    private String actorId;

    private String actorUsername;

    private boolean success;

    /**
     * Why the event happened, for failures.
     *
     * <p>Holds the specific reason, for example {@code BAD_CREDENTIALS} or {@code ACCOUNT_DISABLED},
     * which the HTTP response deliberately does not disclose. The audit trail is the place for the
     * truth; the response is the place for discretion.
     */
    private String reason;

    private String ipAddress;
    private String userAgent;
    private String path;

    private Map<String, Object> details = Collections.emptyMap();

    @Indexed
    private Instant at;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SecurityAuditEvent getEvent() {
        return event;
    }

    public void setEvent(SecurityAuditEvent event) {
        this.event = event;
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

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? Collections.emptyMap() : details;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }
}
