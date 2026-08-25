package com.orchpilot.workflow.externalform;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints the random token a customer receives, and the hash that is stored in its place.
 *
 * <h2>What makes it a secret and not an identifier</h2>
 *
 * The token is 256 bits from {@link SecureRandom}, URL-safe base64 with no padding — long, non-sequential and
 * non-guessable, which is exactly what the specification forbids a UUID, a database id or a base64-of-something
 * from being. It is single-purpose (it opens one form) and it is never persisted: only {@link #hash(String)} of
 * it is stored, so the token exists in exactly two places — the customer's URL and, briefly, the generation
 * response — and nowhere at rest.
 */
@Component
public class SecureTokenGenerator {

    /** 32 bytes = 256 bits of entropy. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** @return a fresh URL-safe random token to hand to the customer, never stored raw */
    public String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @param rawToken the token from the URL
     * @return its SHA-256, hex-encoded — what is stored and matched
     */
    public String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
