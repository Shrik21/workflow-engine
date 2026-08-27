package com.orchpilot.workflow.externalform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token is a secret, not an identifier: long, random, non-repeating, and stored only as a hash.
 */
class SecureTokenGeneratorTest {

    private final SecureTokenGenerator generator = new SecureTokenGenerator();

    @Test
    @DisplayName("tokens are long, URL-safe and never repeat")
    void tokensAreRandomAndLong() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = generator.newRawToken();
            // 32 bytes base64url without padding ~ 43 characters, and URL-safe (no +, /, =).
            assertThat(token).hasSizeGreaterThanOrEqualTo(43).doesNotContainAnyWhitespaces();
            assertThat(token).doesNotContain("+", "/", "=");
            assertThat(seen.add(token)).as("token must be unique").isTrue();
        }
    }

    @Test
    @DisplayName("hashing is deterministic, and different tokens hash differently")
    void hashIsDeterministic() {
        String token = generator.newRawToken();
        assertThat(generator.hash(token)).isEqualTo(generator.hash(token));
        assertThat(generator.hash(token)).hasSize(64); // SHA-256 hex
        assertThat(generator.hash(generator.newRawToken())).isNotEqualTo(generator.hash(token));
    }

    @Test
    @DisplayName("the stored hash does not reveal the token")
    void hashIsNotTheToken() {
        String token = generator.newRawToken();
        assertThat(generator.hash(token)).isNotEqualTo(token);
    }
}
