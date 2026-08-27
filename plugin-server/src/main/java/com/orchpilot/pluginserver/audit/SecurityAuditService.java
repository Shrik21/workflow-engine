package com.orchpilot.pluginserver.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the security trail.
 *
 * <h2>It must never be the reason a request fails</h2>
 *
 * Every method here swallows its own failures and logs them. An audit write that throws would turn a
 * successful sign-in into an error, which trades a missing row for a broken login — a bad exchange. A failure
 * to record is visible in the application log, where an operator will see it.
 *
 * <h2>What goes in a row</h2>
 *
 * Who, what, when, from where, and whether it worked. Never a password, never a token, never a hash. The
 * details map carries names and decisions: which permission was missing, how many attempts preceded a lock.
 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    /** Cap on a recorded header, so a hostile client cannot write unbounded data into the collection. */
    private static final int MAX_AGENT_LENGTH = 256;

    private final SecurityAuditRepository repository;

    public SecurityAuditService(SecurityAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one event.
     *
     * @param action     what happened
     * @param username   who it concerned, even when no such account exists
     * @param userId     their id, or null
     * @param success    whether it succeeded
     * @param request    the request it happened on, for address and agent; may be null
     * @param details    names and decisions, never values
     */
    public void record(SecurityAuditLog.Action action, String username, String userId, boolean success,
                       HttpServletRequest request, Map<String, Object> details) {
        try {
            SecurityAuditLog entry = new SecurityAuditLog();
            entry.setAction(action);
            entry.setUsername(username);
            entry.setUserId(userId);
            entry.setSuccess(success);
            entry.setTimestamp(Instant.now());
            entry.setDetails(details == null ? new LinkedHashMap<>() : details);
            if (request != null) {
                entry.setIpAddress(addressOf(request));
                entry.setUserAgent(truncate(request.getHeader("User-Agent")));
            }
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Could not write a security audit entry for {} on {}: {}", action, username,
                    ex.getMessage());
        }
    }

    /**
     * Records an event about a thing, such as a user or a role.
     *
     * @param action     what happened
     * @param actor      who did it
     * @param resource   the kind of thing
     * @param resourceId which one
     * @param request    the request, may be null
     * @param details    names and decisions
     */
    public void recordOn(SecurityAuditLog.Action action, String actor, String resource, String resourceId,
                         HttpServletRequest request, Map<String, Object> details) {
        try {
            SecurityAuditLog entry = new SecurityAuditLog();
            entry.setAction(action);
            entry.setUsername(actor);
            entry.setResource(resource);
            entry.setResourceId(resourceId);
            entry.setSuccess(true);
            entry.setTimestamp(Instant.now());
            entry.setDetails(details == null ? new LinkedHashMap<>() : details);
            if (request != null) {
                entry.setIpAddress(addressOf(request));
                entry.setUserAgent(truncate(request.getHeader("User-Agent")));
            }
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Could not write a security audit entry for {} on {} {}: {}", action, resource,
                    resourceId, ex.getMessage());
        }
    }

    /**
     * The caller's address.
     *
     * <p>Reads {@code X-Forwarded-For} first, because the registry normally sits behind a proxy and the socket
     * address would otherwise be the proxy's for every request in the collection. The header is client-supplied
     * and therefore not evidence: it is recorded for triage, never used to authorise or to rate-limit.
     */
    private String addressOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return truncate(comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim());
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_AGENT_LENGTH ? value.substring(0, MAX_AGENT_LENGTH) : value;
    }
}
