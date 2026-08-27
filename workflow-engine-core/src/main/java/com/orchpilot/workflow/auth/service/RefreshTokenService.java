package com.orchpilot.workflow.auth.service;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.model.RefreshToken;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p><b>Opaque, not a JWT.</b> A refresh token's entire purpose is to be revocable, and revocation needs
 * server-side state regardless. Making it a JWT would add signature verification on top of the database
 * lookup it still needs, while making a stolen token unrevokable until expiry.
 *
 * <p><b>Stored as a SHA-256 hash.</b> A dump of {@code refresh_tokens} yields nothing presentable, exactly
 * as for passwords. SHA-256 rather than Argon2id is correct here and the difference matters: a password is
 * low-entropy and needs a deliberately slow hash, while this token is 256 bits of cryptographic
 * randomness, so brute force is not a threat and a slow hash would only tax every legitimate refresh.
 *
 * <p><b>Rotation with reuse detection.</b> Every exchange revokes the presented token and issues a new
 * one. Presenting an already-revoked token means either a replay or a theft, so the whole token family is
 * revoked and the user is forced to authenticate again. Without that, a stolen token stays usable
 * alongside the legitimate one for its full lifetime.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 bits of entropy, which puts guessing out of reach and makes a slow hash unnecessary. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final AuthProperties.Jwt properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties) {
        this.repository = repository;
        this.properties = properties.getJwt();
    }

    /**
     * Issues a token for a new login, starting a fresh rotation family.
     *
     * @param user       the authenticated user
     * @param deviceInfo coarse client description, for session display
     * @param ipAddress  client address, for audit
     * @return the raw token, which is the only time it exists outside the client
     */
    public IssuedToken issueForLogin(User user, String deviceInfo, String ipAddress) {
        enforceSessionLimit(user.getId());
        return issue(user.getId(), UUID.randomUUID().toString(), deviceInfo, ipAddress);
    }

    /**
     * Exchanges a token for a new pair.
     *
     * <p>The presented token is revoked before the replacement is persisted, so a crash between the two
     * leaves the user needing to log in again rather than leaving a token valid twice.
     *
     * @param rawToken   token presented by the client
     * @param deviceInfo coarse client description
     * @param ipAddress  client address
     * @return the outcome, which the caller must inspect: a revoked token is a security event, not a
     *         routine failure
     */
    public RotationResult rotate(String rawToken, String deviceInfo, String ipAddress) {
        if (rawToken == null || rawToken.isBlank()) {
            return RotationResult.invalid();
        }
        Optional<RefreshToken> found = repository.findByTokenHash(hash(rawToken));
        if (found.isEmpty()) {
            return RotationResult.invalid();
        }

        RefreshToken existing = found.get();
        Instant now = Instant.now();

        if (existing.isRevoked()) {
            // Replay of a token already exchanged. Either the client kept a stale copy, or someone
            // else has one. Both are handled the same way: revoke the family and make everyone
            // re-authenticate. Failing safe matters more than being convenient here.
            revokeFamily(existing.getFamilyId(), "token reuse detected");
            log.warn("Refresh token reuse detected for user {}; revoked token family {}",
                    existing.getUserId(), existing.getFamilyId());
            return RotationResult.reuseDetected(existing.getUserId());
        }
        if (existing.getExpiresAt() == null || !existing.getExpiresAt().isAfter(now)) {
            return RotationResult.expired(existing.getUserId());
        }

        IssuedToken replacement = issue(existing.getUserId(), existing.getFamilyId(), deviceInfo, ipAddress);

        existing.setRevoked(true);
        existing.setRevokedAt(now);
        existing.setRevokedReason("rotated");
        existing.setReplacedByTokenId(replacement.record().getId());
        repository.save(existing);

        return RotationResult.rotated(existing.getUserId(), replacement);
    }

    /**
     * Revokes a single token, as on logout.
     *
     * @param rawToken token presented by the client
     * @param reason   why, recorded for audit
     * @return the user the token belonged to, or empty when the token was unknown
     */
    public Optional<String> revoke(String rawToken, String reason) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTokenHash(hash(rawToken)).map(token -> {
            if (!token.isRevoked()) {
                token.setRevoked(true);
                token.setRevokedAt(Instant.now());
                token.setRevokedReason(reason);
                repository.save(token);
            }
            return token.getUserId();
        });
    }

    /**
     * Revokes every live token for a user.
     *
     * <p>Used when an account is disabled, locked, deleted, or has its password changed. A password change
     * that left existing sessions alive would make "change your password" useless as a response to a
     * compromise.
     *
     * @param userId the user
     * @param reason why, recorded for audit
     * @return how many tokens were revoked
     */
    public int revokeAllForUser(String userId, String reason) {
        List<RefreshToken> live = repository.findByUserIdAndRevokedFalse(userId);
        Instant now = Instant.now();
        for (RefreshToken token : live) {
            token.setRevoked(true);
            token.setRevokedAt(now);
            token.setRevokedReason(reason);
        }
        if (!live.isEmpty()) {
            repository.saveAll(live);
        }
        return live.size();
    }

    /**
     * @param userId the user
     * @return their live sessions, most recent first
     */
    public List<RefreshToken> liveSessions(String userId) {
        return repository.findByUserIdAndRevokedFalse(userId).stream()
                .sorted(Comparator.comparing(RefreshToken::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private IssuedToken issue(String userId, String familyId, String deviceInfo, String ipAddress) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        // URL-safe and unpadded so the value is safe in a cookie and in a JSON body without escaping.
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Instant now = Instant.now();
        RefreshToken record = new RefreshToken();
        record.setTokenHash(hash(rawToken));
        record.setUserId(userId);
        record.setFamilyId(familyId);
        record.setCreatedAt(now);
        record.setExpiresAt(now.plusMillis(properties.getRefreshTokenExpiration()));
        record.setDeviceInfo(truncate(deviceInfo));
        record.setIpAddress(ipAddress);
        record.setRevoked(false);

        return new IssuedToken(rawToken, repository.save(record));
    }

    private void revokeFamily(String familyId, String reason) {
        if (familyId == null) {
            return;
        }
        List<RefreshToken> family = repository.findByFamilyIdAndRevokedFalse(familyId);
        Instant now = Instant.now();
        for (RefreshToken token : family) {
            token.setRevoked(true);
            token.setRevokedAt(now);
            token.setRevokedReason(reason);
        }
        if (!family.isEmpty()) {
            repository.saveAll(family);
        }
    }

    /**
     * Keeps the number of concurrent sessions bounded by revoking the oldest.
     *
     * <p>Without a cap, a client that never logs out accumulates a live token per login for the whole
     * refresh lifetime, each of which is a credential that could be stolen.
     */
    private void enforceSessionLimit(String userId) {
        int max = properties.getMaxSessionsPerUser();
        if (max <= 0) {
            return;
        }
        List<RefreshToken> live = repository.findByUserIdAndRevokedFalse(userId);
        if (live.size() < max) {
            return;
        }
        live.stream()
                .sorted(Comparator.comparing(RefreshToken::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(live.size() - max + 1L)
                .forEach(token -> {
                    token.setRevoked(true);
                    token.setRevokedAt(Instant.now());
                    token.setRevokedReason("session limit reached");
                    repository.save(token);
                });
    }

    /**
     * SHA-256 of the raw token, hex encoded.
     *
     * <p>Deterministic and unsalted on purpose: the lookup is by hash, so a per-record salt would make it
     * impossible to find the row. That is safe here only because the input is high-entropy random, which is
     * exactly why the same reasoning must never be applied to passwords.
     */
    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JDK specification, so this cannot happen on a valid platform.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    /**
     * A newly issued token.
     *
     * @param rawToken the value to hand the client; never persisted
     * @param record   the stored document, which holds only the hash
     */
    public record IssuedToken(String rawToken, RefreshToken record) {
    }

    /** Why a rotation attempt ended as it did. */
    public enum RotationStatus {
        /** Exchanged successfully. */
        ROTATED,
        /** No such token. Either never issued, or already reclaimed by the TTL index. */
        INVALID,
        /** Found but past its expiry. */
        EXPIRED,
        /** Found but already revoked: a replay or a theft. The family has been revoked. */
        REUSE_DETECTED
    }

    /**
     * Outcome of a rotation attempt.
     *
     * @param status  what happened
     * @param userId  the token's owner, when known, for audit attribution
     * @param issued  the replacement, only when {@code status} is {@link RotationStatus#ROTATED}
     */
    public record RotationResult(RotationStatus status, String userId, IssuedToken issued) {

        static RotationResult invalid() {
            return new RotationResult(RotationStatus.INVALID, null, null);
        }

        static RotationResult expired(String userId) {
            return new RotationResult(RotationStatus.EXPIRED, userId, null);
        }

        static RotationResult reuseDetected(String userId) {
            return new RotationResult(RotationStatus.REUSE_DETECTED, userId, null);
        }

        static RotationResult rotated(String userId, IssuedToken issued) {
            return new RotationResult(RotationStatus.ROTATED, userId, issued);
        }

        /** @return whether a new pair was issued */
        public boolean isSuccess() {
            return status == RotationStatus.ROTATED;
        }
    }
}
