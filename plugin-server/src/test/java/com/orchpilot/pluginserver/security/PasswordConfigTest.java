package com.orchpilot.pluginserver.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder that verifies every password and every client secret in this service.
 *
 * <p>The legacy case is the one worth pinning. A value written before hashes carried an {@code {algorithm}}
 * prefix has to keep verifying, and the obvious-looking fallback —
 * {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()} — is itself a delegating encoder that
 * throws "each password must have a password encoding prefix" for precisely those values. That defect stopped
 * the workflow engine obtaining a registry token, which surfaced as a catalogue sync that could not
 * authenticate, with nothing in the message to suggest the cause was a hash written months earlier.
 */
class PasswordConfigTest {

    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder(new AuthProperties());

    @Test
    @DisplayName("new hashes are Argon2id, and carry the prefix that says so")
    void writesArgon2() {
        String hash = encoder.encode("a-strong-password");

        assertTrue(hash.startsWith("{argon2}"), hash);
        assertTrue(encoder.matches("a-strong-password", hash));
        assertFalse(encoder.matches("a-different-password", hash));
    }

    @Test
    @DisplayName("a prefixed BCrypt hash still verifies")
    void verifiesPrefixedBcrypt() {
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(10).encode("a-legacy-password");

        assertTrue(encoder.matches("a-legacy-password", hash));
        assertFalse(encoder.matches("a-guess", hash));
    }

    @Test
    @DisplayName("an unprefixed BCrypt hash verifies rather than throwing")
    void verifiesUnprefixedBcrypt() {
        // How the bootstrap service client's secret was stored by an earlier release: a raw $2a$ value. It has
        // to verify, because the alternative is that an existing installation cannot authenticate anything it
        // wrote before the prefixes arrived.
        String hash = new BCryptPasswordEncoder(10).encode("a-bootstrapped-client-secret");

        assertDoesNotThrow(() -> encoder.matches("a-bootstrapped-client-secret", hash));
        assertTrue(encoder.matches("a-bootstrapped-client-secret", hash));
        assertFalse(encoder.matches("not-the-secret", hash));
    }

    @Test
    @DisplayName("a stored value that is not a hash at all is refused, not treated as a match")
    void refusesGarbage() {
        // A plaintext secret that found its way into the database must never authenticate.
        assertFalse(encoder.matches("plaintext", "plaintext"));
    }
}
