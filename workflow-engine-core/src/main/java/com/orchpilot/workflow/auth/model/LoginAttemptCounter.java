package com.orchpilot.workflow.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Failed-login counter for one identifier, used by the brute-force throttle.
 *
 * <p>Stored in MongoDB rather than in memory on purpose: the engine is designed to run as several
 * instances behind a load balancer, and an in-memory counter would give an attacker one full budget
 * of attempts per instance. A shared counter also survives a restart, so restarting the service is
 * not a way to clear a lockout.
 *
 * <p>The identifier is either {@code user:<username>} or {@code ip:<address>}. Both are counted,
 * because they defeat different attacks: per-username stops a distributed attempt on one account, and
 * per-IP stops one host spraying many usernames.
 *
 * <p>A TTL index on {@code expiresAt} lets MongoDB discard stale counters, which is also what ends a
 * lockout: nothing has to run to release it.
 */
@Document(collection = "login_attempts")
public class LoginAttemptCounter {

    @Id
    private String id;

    @Indexed
    private String identifier;

    private int failedAttempts;

    private Instant firstFailureAt;
    private Instant lastFailureAt;

    /** When the counter, and therefore any lockout it caused, stops applying. */
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getFirstFailureAt() {
        return firstFailureAt;
    }

    public void setFirstFailureAt(Instant firstFailureAt) {
        this.firstFailureAt = firstFailureAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public void setLastFailureAt(Instant lastFailureAt) {
        this.lastFailureAt = lastFailureAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
