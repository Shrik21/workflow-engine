package com.orchpilot.pluginserver.auth;

import com.orchpilot.pluginserver.audit.SecurityAuditLog;
import com.orchpilot.pluginserver.audit.SecurityAuditService;
import com.orchpilot.pluginserver.security.AuthProperties;
import com.orchpilot.pluginserver.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <h2>Rotation, and what it is for</h2>
 *
 * Every refresh consumes the presented token and issues a new one. That bounds the value of a stolen token to
 * a single use, and — more usefully — makes theft detectable: if a token that has already been rotated is
 * presented again, two parties hold it. One of them is an attacker and there is no way to tell which, so the
 * whole chain is revoked and both are made to sign in again. A brief interruption for the real user is a fair
 * price for ending an active session hijack.
 *
 * <h2>Stored as a hash</h2>
 *
 * The token is 256 bits from a secure generator and only its SHA-256 is stored, so a leaked database yields
 * nothing usable. SHA-256 rather than a password hash because there is no low-entropy secret to protect and no
 * reason to spend 50ms on a path that runs on every refresh.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final SecurityAuditService audit;

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties,
                               SecurityAuditService audit) {
        this.repository = repository;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * A newly issued refresh token.
     *
     * @param value     the token, which the caller must return to the client and must never store
     * @param expiresAt when it stops being accepted
     */
    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    /**
     * Issues a token for an account.
     *
     * @param user    the account
     * @param request the request, for the address and agent recorded against the session
     * @return the token, which exists in plaintext only in this return value
     */
    public IssuedRefreshToken issue(User user, HttpServletRequest request) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RefreshTokenRecord record = new RefreshTokenRecord();
        record.setTokenHash(hash(value));
        record.setUserId(user.getId());
        record.setUsername(user.getUsername());
        record.setCreatedAt(Instant.now());
        record.setExpiresAt(Instant.now().plus(properties.getJwt().getRefreshTokenTtl()));
        if (request != null) {
            record.setDeviceInfo(header(request, "User-Agent"));
            record.setIpAddress(request.getRemoteAddr());
        }
        repository.save(record);
        return new IssuedRefreshToken(value, record.getExpiresAt());
    }

    /**
     * The outcome of presenting a refresh token, before the account is known.
     *
     * <p>A closed set rather than an exception per case, because the caller answers all of them the same way
     * to the client — one message, no hint about which — while recording them differently.
     *
     * <p>Validation is separate from rotation on purpose: which account a token belongs to is only knowable
     * from the stored record, so the caller cannot load the account until this has run.
     */
    public sealed interface RefreshOutcome {

        /** Recognised, live, and not yet rotated. The account named on it still has to be checked. */
        record Valid(RefreshTokenRecord record) implements RefreshOutcome {
        }

        /** No such token, or it expired, or it was revoked. */
        record Rejected(String reason) implements RefreshOutcome {
        }

        /** Presented after it had already been rotated. The whole chain has been revoked. */
        record ReuseDetected(String userId, String username) implements RefreshOutcome {
        }
    }

    /**
     * Checks a presented token, without yet issuing anything.
     *
     * @param presented the token from the client
     * @param request   the request, for the audit trail
     * @return what the token is
     */
    public RefreshOutcome validate(String presented, HttpServletRequest request) {
        if (presented == null || presented.isBlank()) {
            return new RefreshOutcome.Rejected("absent");
        }
        Optional<RefreshTokenRecord> found = repository.findByTokenHash(hash(presented));
        if (found.isEmpty()) {
            return new RefreshOutcome.Rejected("unknown");
        }
        RefreshTokenRecord record = found.get();

        if (record.isAlreadyRotated()) {
            // Two parties hold this token. Which one is the attacker is unknowable, so neither keeps access.
            revokeAllFor(record.getUserId(), "refresh token reuse detected");
            log.warn("Refresh token reuse detected for '{}'; every session for that account was revoked",
                    record.getUsername());
            audit.record(SecurityAuditLog.Action.TOKEN_REUSE_DETECTED, record.getUsername(),
                    record.getUserId(), false, request, Map.of("action", "all sessions revoked"));
            return new RefreshOutcome.ReuseDetected(record.getUserId(), record.getUsername());
        }
        if (!record.isUsable()) {
            return new RefreshOutcome.Rejected(record.isRevoked() ? "revoked" : "expired");
        }
        return new RefreshOutcome.Valid(record);
    }

    /**
     * Consumes a validated token and issues its replacement.
     *
     * @param consumed the record returned by {@link #validate}
     * @param user     the account it belongs to, loaded by the caller
     * @param request  the request, for the new session's metadata
     * @return the replacement token
     */
    public IssuedRefreshToken rotate(RefreshTokenRecord consumed, User user, HttpServletRequest request) {
        IssuedRefreshToken replacement = issue(user, request);
        consumed.setReplacedBy(hash(replacement.value()));
        consumed.setLastUsedAt(Instant.now());
        consumed.setRevoked(true);
        consumed.setRevokedAt(Instant.now());
        consumed.setRevokedReason("rotated");
        repository.save(consumed);
        return replacement;
    }

    /**
     * Whether a token predates the account's last credential change.
     *
     * <p>This is what makes a password change end every other session without hunting for their tokens: the
     * sessions are not deleted, they simply stop being honoured.
     *
     * @param record the token record
     * @param user   the account
     * @return whether the token was issued before the password changed
     */
    public boolean predatesCredentialChange(RefreshTokenRecord record, User user) {
        return user.getCredentialsChangedAt() != null
                && record.getCreatedAt() != null
                && record.getCreatedAt().isBefore(user.getCredentialsChangedAt());
    }

    /**
     * Revokes one token, which is what signing out does.
     *
     * @param presented the token
     * @return whether a token was found to revoke
     */
    public boolean revoke(String presented) {
        Optional<RefreshTokenRecord> found = repository.findByTokenHash(hash(presented));
        if (found.isEmpty()) {
            return false;
        }
        RefreshTokenRecord record = found.get();
        record.setRevoked(true);
        record.setRevokedAt(Instant.now());
        record.setRevokedReason("signed out");
        repository.save(record);
        return true;
    }

    /**
     * Revokes every session for an account.
     *
     * <p>Called on a password change, on a reuse detection, and when an account is disabled. Each of those is
     * a statement that whoever holds the existing tokens should stop being trusted.
     *
     * @param userId the account
     * @param reason recorded on each token
     * @return how many were revoked
     */
    public int revokeAllFor(String userId, String reason) {
        List<RefreshTokenRecord> active = repository.findByUserIdAndRevokedFalse(userId);
        for (RefreshTokenRecord record : active) {
            record.setRevoked(true);
            record.setRevokedAt(Instant.now());
            record.setRevokedReason(reason);
        }
        repository.saveAll(active);
        return active.size();
    }

    /** @param userId the account @return its live sessions, for a profile screen */
    public List<RefreshTokenRecord> activeSessions(String userId) {
        return repository.findByUserIdAndRevokedFalse(userId);
    }

    /**
     * SHA-256, hex.
     *
     * <p>The stored form. Deterministic on purpose: a lookup by hash is how a presented token is recognised,
     * which a salted hash would make impossible without reading every row.
     */
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null) {
            return null;
        }
        return value.length() > 256 ? value.substring(0, 256) : value;
    }
}
