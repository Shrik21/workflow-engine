package com.orchpilot.workflow.auth.service;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.model.LoginAttemptCounter;
import com.orchpilot.workflow.auth.repository.LoginAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Brute-force protection for the login endpoint.
 *
 * <p>Counts failures against two independent identifiers, because each defeats a different attack:
 *
 * <ul>
 *   <li><b>Per username.</b> Stops a distributed attempt on one account, where every request comes from a
 *       different address.</li>
 *   <li><b>Per source address.</b> Stops one host spraying a common password across many usernames, which a
 *       per-username counter never notices because no single account accumulates failures. The per-address
 *       threshold is higher, since a whole office can share one address.</li>
 * </ul>
 *
 * <p>State lives in MongoDB rather than in memory. The engine is built to run as several instances, and an
 * in-memory counter would hand an attacker one full budget per instance; it would also mean restarting the
 * service cleared every lockout.
 *
 * <p>A lockout is temporary and entirely separate from the administrative {@code accountLocked} flag on the
 * user. Conflating them would let an attacker permanently lock a real user out simply by guessing at them.
 */
@Service
public class LoginThrottleService {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleService.class);

    private static final String USER_PREFIX = "user:";
    private static final String IP_PREFIX = "ip:";

    private final LoginAttemptRepository repository;
    private final AuthProperties.Lockout properties;

    public LoginThrottleService(LoginAttemptRepository repository, AuthProperties properties) {
        this.repository = repository;
        this.properties = properties.getLockout();
    }

    /**
     * Whether this attempt should be refused before the password is even checked.
     *
     * <p>Checked first so that a locked-out attacker cannot keep spending Argon2id verifications, which
     * would otherwise turn the throttle into a denial-of-service amplifier against the server itself.
     *
     * @param username  submitted username, may be {@code null}
     * @param ipAddress client address, may be {@code null}
     * @return whether the attempt is currently locked out
     */
    public boolean isLockedOut(String username, String ipAddress) {
        if (!properties.isEnabled()) {
            return false;
        }
        Instant now = Instant.now();
        if (exceeds(userKey(username), properties.getMaxFailedAttempts(), now)) {
            return true;
        }
        return properties.isTrackByIpAddress()
                && exceeds(ipKey(ipAddress), properties.getMaxFailedAttemptsPerIp(), now);
    }

    /**
     * Records a failure against both identifiers.
     *
     * @param username  submitted username, recorded even when no such account exists
     * @param ipAddress client address
     */
    public void recordFailure(String username, String ipAddress) {
        if (!properties.isEnabled()) {
            return;
        }
        increment(userKey(username));
        if (properties.isTrackByIpAddress()) {
            increment(ipKey(ipAddress));
        }
    }

    /**
     * Clears the counters after a successful login.
     *
     * <p>Only the username counter is cleared, not the address counter. A successful login from an address
     * that has been failing against many accounts is exactly what a successful credential-stuffing attempt
     * looks like, so it should not reset that address's budget.
     *
     * @param username the user who authenticated
     */
    public void recordSuccess(String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String key = userKey(username);
        if (key == null) {
            return;
        }
        try {
            repository.deleteByIdentifier(key);
        } catch (RuntimeException ex) {
            log.debug("Could not clear login counter for {}: {}", key, ex.getMessage());
        }
    }

    /**
     * @param username the account
     * @return how many attempts remain before lockout, for logging rather than for the response, which
     *         must not tell an attacker how much budget is left
     */
    public int remainingAttempts(String username) {
        String key = userKey(username);
        if (key == null || !properties.isEnabled()) {
            return properties.getMaxFailedAttempts();
        }
        return repository.findByIdentifier(key)
                .filter(counter -> isCurrent(counter, Instant.now()))
                .map(counter -> Math.max(0, properties.getMaxFailedAttempts() - counter.getFailedAttempts()))
                .orElse(properties.getMaxFailedAttempts());
    }

    /** @return how long a lockout lasts, in seconds, for the audit record */
    public long lockoutSeconds() {
        return properties.getLockoutMillis() / 1000;
    }

    private boolean exceeds(String key, int threshold, Instant now) {
        if (key == null) {
            return false;
        }
        return repository.findByIdentifier(key)
                .filter(counter -> isCurrent(counter, now))
                .map(counter -> counter.getFailedAttempts() >= threshold)
                .orElse(false);
    }

    private void increment(String key) {
        if (key == null) {
            return;
        }
        Instant now = Instant.now();
        try {
            Optional<LoginAttemptCounter> existing = repository.findByIdentifier(key);
            LoginAttemptCounter counter = existing.filter(c -> isCurrent(c, now)).orElseGet(() -> {
                LoginAttemptCounter fresh = existing.orElseGet(LoginAttemptCounter::new);
                fresh.setIdentifier(key);
                fresh.setFailedAttempts(0);
                fresh.setFirstFailureAt(now);
                return fresh;
            });

            counter.setFailedAttempts(counter.getFailedAttempts() + 1);
            counter.setLastFailureAt(now);

            // Once the threshold is reached the window extends to the lockout duration, which is what
            // turns a counter into a lock. Before that it is a sliding window over recent failures.
            boolean locked = counter.getFailedAttempts() >= thresholdFor(key);
            counter.setExpiresAt(now.plusMillis(
                    locked ? properties.getLockoutMillis() : properties.getWindowMillis()));

            repository.save(counter);
            if (locked) {
                log.warn("Login lockout applied to {} for {} seconds after {} failed attempts",
                        key, lockoutSeconds(), counter.getFailedAttempts());
            }
        } catch (RuntimeException ex) {
            // A throttle that breaks logins when the database hiccups is worse than one that
            // occasionally misses a count.
            log.error("Could not record a failed login for {}: {}", key, ex.getMessage());
        }
    }

    private int thresholdFor(String key) {
        return key.startsWith(IP_PREFIX) ? properties.getMaxFailedAttemptsPerIp() : properties.getMaxFailedAttempts();
    }

    private boolean isCurrent(LoginAttemptCounter counter, Instant now) {
        // The TTL index reclaims documents eventually, but MongoDB runs it about once a minute, so an
        // expired counter can still be readable. Checking the timestamp keeps a lockout from outlasting
        // its configured duration.
        return counter.getExpiresAt() != null && counter.getExpiresAt().isAfter(now);
    }

    private static String userKey(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return USER_PREFIX + username.trim().toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        return IP_PREFIX + ipAddress.trim();
    }
}
