package com.orchpilot.workflow.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the security audit trail.
 *
 * <p>The API takes a typed {@link SecurityAuditEvent} and structured details rather than a free-text
 * message. That is a deliberate constraint: there is no parameter into which a caller could pass a
 * password or a token because they thought it would be useful context. As a second line of defence,
 * {@link #sanitise} drops any detail whose key looks like a credential, so even a mistake does not
 * become a disclosure.
 *
 * <p>Failures to audit are logged and swallowed. An audit write must never break the operation it is
 * recording: refusing a valid login because MongoDB was briefly unavailable would turn a logging problem
 * into an outage. Where an audit trail is a hard compliance requirement, that trade goes the other way,
 * and this is the single method to change.
 */
@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);

    /**
     * Detail keys that are never persisted.
     *
     * <p>Matched as substrings, case-insensitively, so {@code newPassword} and {@code refresh_token} are
     * both caught.
     */
    private static final Set<String> FORBIDDEN_KEY_FRAGMENTS = Set.of(
            "password", "passwd", "secret", "token", "jwt", "credential", "apikey", "api_key",
            "authorization", "cookie", "hash");

    private static final int MAX_DETAIL_LENGTH = 500;

    private final SecurityAuditLogRepository repository;

    public SecurityAuditService(SecurityAuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a successful event.
     *
     * @param event    what happened
     * @param userId   subject's user id, may be {@code null}
     * @param username subject's username, may be {@code null}
     * @param request  current request, for IP and user agent; may be {@code null}
     * @param details  structured context; credential-looking keys are dropped
     */
    public void success(SecurityAuditEvent event, String userId, String username,
                        HttpServletRequest request, Map<String, Object> details) {
        write(event, userId, username, true, null, request, details);
    }

    /**
     * Records a failed event.
     *
     * @param event    what happened
     * @param userId   subject's user id, may be {@code null} when the account does not exist
     * @param username username as supplied by the caller
     * @param reason   specific machine-readable reason, recorded here but never returned to the client
     * @param request  current request; may be {@code null}
     */
    public void failure(SecurityAuditEvent event, String userId, String username, String reason,
                        HttpServletRequest request) {
        write(event, userId, username, false, reason, request, Map.of());
    }

    /**
     * Records an administrative action, attributing both the actor and the subject.
     *
     * @param event         what happened
     * @param actorId       administrator's user id
     * @param actorUsername administrator's username
     * @param subjectId     affected user's id
     * @param subjectName   affected user's username
     * @param request       current request; may be {@code null}
     * @param details       structured context, for example the old and new role sets
     */
    public void administrative(SecurityAuditEvent event, String actorId, String actorUsername,
                               String subjectId, String subjectName,
                               HttpServletRequest request, Map<String, Object> details) {
        SecurityAuditLog entry = base(event, subjectId, subjectName, true, null, request, details);
        entry.setActorId(actorId);
        entry.setActorUsername(actorUsername);
        persist(entry);
    }

    private void write(SecurityAuditEvent event, String userId, String username, boolean success,
                       String reason, HttpServletRequest request, Map<String, Object> details) {
        persist(base(event, userId, username, success, reason, request, details));
    }

    private SecurityAuditLog base(SecurityAuditEvent event, String userId, String username, boolean success,
                                  String reason, HttpServletRequest request, Map<String, Object> details) {
        SecurityAuditLog entry = new SecurityAuditLog();
        entry.setEvent(event);
        entry.setUserId(userId);
        entry.setUsername(username);
        entry.setSuccess(success);
        entry.setReason(reason);
        entry.setAt(Instant.now());
        entry.setDetails(sanitise(details));
        if (request != null) {
            entry.setIpAddress(clientAddress(request));
            entry.setUserAgent(truncate(request.getHeader("User-Agent")));
            entry.setPath(request.getRequestURI());
        }
        return entry;
    }

    private void persist(SecurityAuditLog entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException ex) {
            // Never let auditing break the operation being audited.
            log.error("Failed to write security audit record for {}: {}", entry.getEvent(), ex.getMessage());
        }
    }

    /**
     * Drops credential-looking keys and truncates long values.
     *
     * <p>The belt to the typed API's braces. A caller who adds {@code Map.of("newPassword", raw)} in a
     * hurry gets a record without it rather than a plaintext password in the database.
     */
    private Map<String, Object> sanitise(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(lower::contains)) {
                log.warn("Refusing to audit detail '{}': the key suggests a credential", key);
                continue;
            }
            Object value = entry.getValue();
            clean.put(key, value instanceof String text ? truncate(text) : value);
        }
        return clean;
    }

    /**
     * Best-effort client address.
     *
     * <p>{@code X-Forwarded-For} is honoured because the engine normally sits behind a reverse proxy,
     * where the socket address is the proxy's. The header is client-controlled and therefore only as
     * trustworthy as the proxy that sets it, which is why it is used for audit context and rate limiting
     * rather than for any authorization decision.
     */
    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            return truncate(first.trim());
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH) + "…";
    }
}
