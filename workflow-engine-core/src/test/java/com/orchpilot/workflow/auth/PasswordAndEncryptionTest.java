package com.orchpilot.workflow.auth;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.model.Permission;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.service.PasswordPolicyException;
import com.orchpilot.workflow.auth.service.PasswordService;
import com.orchpilot.workflow.encryption.AesGcmEncryptionService;
import com.orchpilot.workflow.encryption.EncryptionException;
import com.orchpilot.workflow.encryption.SecretEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two mechanisms that must never be confused: one-way password hashing and reversible secret
 * encryption.
 *
 * <p>The most important assertions here are the negative ones. A stored password must not be recoverable,
 * and a tampered ciphertext must not decrypt. Both are properties it is easy to lose in a refactor without
 * any test noticing, because the happy path keeps working either way.
 */
class PasswordAndEncryptionTest {

    /** Argon2id at reduced cost: correctness is identical and the suite stays fast. */
    private static PasswordEncoder testEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", new Argon2PasswordEncoder(16, 32, 1, 1024, 1));
        encoders.put("bcrypt", new BCryptPasswordEncoder(4));
        return new DelegatingPasswordEncoder("argon2", encoders);
    }

    private static AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.getPassword().setMemoryKb(1024);
        properties.getPassword().setIterations(1);
        return properties;
    }

    @Nested
    @DisplayName("Password hashing")
    class Hashing {

        private PasswordService passwords;

        @BeforeEach
        void setUp() {
            passwords = new PasswordService(testEncoder(), properties());
        }

        @Test
        @DisplayName("stores an Argon2id digest, never anything resembling the password")
        void hashesWithArgon2id() {
            String raw = "Correct-Horse-Battery9!";
            String hash = passwords.hash(raw);

            assertThat(hash).startsWith("{argon2}$argon2id$");
            // The decisive assertion: the stored value must not contain the password in any form.
            assertThat(hash).doesNotContain(raw);
            assertThat(Base64.getEncoder().encodeToString(raw.getBytes())).isNotEqualTo(hash);
        }

        @Test
        @DisplayName("produces a different hash each time, so identical passwords are not detectable")
        void saltsEveryHash() {
            String raw = "Correct-Horse-Battery9!";
            assertThat(passwords.hash(raw)).isNotEqualTo(passwords.hash(raw));
        }

        @Test
        @DisplayName("verifies the right password and rejects a wrong one")
        void verifies() {
            String hash = passwords.hash("Correct-Horse-Battery9!");

            assertThat(passwords.verify("Correct-Horse-Battery9!", hash)).isTrue();
            assertThat(passwords.verify("correct-horse-battery9!", hash)).isFalse();
            assertThat(passwords.verify("", hash)).isFalse();
            assertThat(passwords.verify(null, hash)).isFalse();
        }

        @Test
        @DisplayName("accepts a legacy BCrypt hash and marks it for upgrade")
        void supportsBcryptForMigration() {
            String bcrypt = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("Correct-Horse-Battery9!");

            assertThat(passwords.verify("Correct-Horse-Battery9!", bcrypt)).isTrue();
            assertThat(passwords.needsUpgrade(bcrypt)).isTrue();
            assertThat(passwords.needsUpgrade(passwords.hash("Correct-Horse-Battery9!"))).isFalse();
        }

        @Test
        @DisplayName("burns comparable time for an unknown account")
        void dummyVerificationDoesNotThrow() {
            // Guards the timing-oracle defence: the call must be safe for any input, including null,
            // because it runs on the path where no user was found.
            passwords.verifyDummy("anything");
            passwords.verifyDummy(null);
        }
    }

    @Nested
    @DisplayName("Password policy")
    class Policy {

        private PasswordService passwords;

        @BeforeEach
        void setUp() {
            passwords = new PasswordService(testEncoder(), properties());
        }

        @Test
        @DisplayName("reports every violation at once rather than the first")
        void reportsAllViolations() {
            assertThatThrownBy(() -> passwords.validate("short"))
                    .isInstanceOf(PasswordPolicyException.class)
                    .satisfies(thrown -> {
                        var violations = ((PasswordPolicyException) thrown).getViolations();
                        assertThat(violations).hasSizeGreaterThan(1);
                        assertThat(violations).anyMatch(v -> v.contains("12 characters"));
                        assertThat(violations).anyMatch(v -> v.contains("upper-case"));
                    });
        }

        @Test
        @DisplayName("requires length, case, digit and symbol")
        void enforcesComplexity() {
            assertThatThrownBy(() -> passwords.validate("alllowercase1!"))
                    .isInstanceOf(PasswordPolicyException.class);
            assertThatThrownBy(() -> passwords.validate("ALLUPPERCASE1!"))
                    .isInstanceOf(PasswordPolicyException.class);
            assertThatThrownBy(() -> passwords.validate("NoDigitsHere!!"))
                    .isInstanceOf(PasswordPolicyException.class);
            assertThatThrownBy(() -> passwords.validate("NoSymbolsHere1"))
                    .isInstanceOf(PasswordPolicyException.class);
            assertThatThrownBy(() -> passwords.validate("Sh0rt!"))
                    .isInstanceOf(PasswordPolicyException.class);
        }

        @Test
        @DisplayName("rejects a common password even when it satisfies every character rule")
        void rejectsCommonPasswords() {
            // Password123! passes length, case, digit and symbol, and is among the first things tried.
            assertThatThrownBy(() -> passwords.validate("Password123!"))
                    .isInstanceOf(PasswordPolicyException.class)
                    .satisfies(t -> assertThat(((PasswordPolicyException) t).getViolations())
                            .anyMatch(v -> v.contains("commonly used") || v.contains("too common")));
        }

        @Test
        @DisplayName("sees through character substitution")
        void rejectsLeetspeakOfCommonPasswords() {
            assertThatThrownBy(() -> passwords.validate("P@ssw0rd!!!!"))
                    .isInstanceOf(PasswordPolicyException.class);
        }

        @Test
        @DisplayName("accepts a strong password")
        void acceptsStrongPasswords() {
            passwords.validate("Tr0ubador-Zebra!x");
            passwords.validate("qX7$mnvBeeplorp2");
        }
    }

    @Nested
    @DisplayName("Roles and permissions")
    class Roles {

        @Test
        @DisplayName("ADMIN holds every permission")
        void adminHoldsEverything() {
            assertThat(Role.ADMIN.permissions()).containsAll(java.util.List.of(Permission.values()));
        }

        @Test
        @DisplayName("USER cannot manage plugins, users or secrets")
        void userIsLimited() {
            assertThat(Role.USER.grants(Permission.WORKFLOW_CREATE)).isTrue();
            assertThat(Role.USER.grants(Permission.WORKFLOW_EXECUTE)).isTrue();
            assertThat(Role.USER.grants(Permission.EXECUTION_VIEW)).isTrue();

            // The whole point of the USER role.
            assertThat(Role.USER.grants(Permission.PLUGIN_UPLOAD)).isFalse();
            assertThat(Role.USER.grants(Permission.PLUGIN_ACTIVATE)).isFalse();
            assertThat(Role.USER.grants(Permission.USER_VIEW)).isFalse();
            assertThat(Role.USER.grants(Permission.USER_CREATE)).isFalse();
            assertThat(Role.USER.grants(Permission.SECRET_MANAGE)).isFalse();
            assertThat(Role.USER.grants(Permission.WORKFLOW_DELETE)).isFalse();
        }

        @Test
        @DisplayName("authority names carry the ROLE_ prefix only for roles")
        void authorityNaming() {
            assertThat(Role.ADMIN.authority()).isEqualTo("ROLE_ADMIN");
            assertThat(Permission.PLUGIN_UPLOAD.authority()).isEqualTo("PLUGIN_UPLOAD");
        }

        @Test
        @DisplayName("parses names defensively, failing closed on an unknown role")
        void parsesDefensively() {
            assertThat(Role.parse("admin")).contains(Role.ADMIN);
            assertThat(Role.parse("ROLE_ADMIN")).contains(Role.ADMIN);
            assertThat(Role.parse(" User ")).contains(Role.USER);
            // An unrecognised role from an older schema is dropped rather than throwing, so the user
            // simply does not receive its authorities.
            assertThat(Role.parse("SUPERUSER")).isEmpty();
            assertThat(Role.parse(null)).isEmpty();
            assertThat(Role.parse("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Secret encryption")
    class Encryption {

        private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

        private SecretEncryptionService service(String key) {
            AuthProperties properties = new AuthProperties();
            properties.getEncryption().setKey(key);
            properties.getEncryption().setKeyId("test-key");
            return new AesGcmEncryptionService(properties);
        }

        @Test
        @DisplayName("round-trips a value")
        void roundTrips() {
            SecretEncryptionService service = service(KEY);
            String envelope = service.encrypt("SG.super-secret-api-key");

            assertThat(envelope).startsWith("v1:test-key:");
            assertThat(envelope).doesNotContain("super-secret");
            assertThat(service.decrypt(envelope)).isEqualTo("SG.super-secret-api-key");
        }

        @Test
        @DisplayName("uses a fresh nonce, so the same value encrypts differently every time")
        void freshNoncePerCall() {
            SecretEncryptionService service = service(KEY);
            // Nonce reuse under one key is catastrophic for GCM, so this is the property that matters most.
            assertThat(service.encrypt("same")).isNotEqualTo(service.encrypt("same"));
        }

        @Test
        @DisplayName("detects tampering rather than decrypting to garbage")
        void detectsTampering() {
            SecretEncryptionService service = service(KEY);
            String envelope = service.encrypt("https://api.example.com/pay");

            String[] parts = envelope.split(":", 4);
            byte[] cipherText = Base64.getDecoder().decode(parts[3]);
            cipherText[0] ^= 0x01;
            String tampered = String.join(":", parts[0], parts[1], parts[2],
                    Base64.getEncoder().encodeToString(cipherText));

            assertThatThrownBy(() -> service.decrypt(tampered))
                    .isInstanceOf(EncryptionException.class)
                    .hasMessageContaining("altered");
        }

        @Test
        @DisplayName("binds a value to its context, so a ciphertext cannot be moved between records")
        void bindsContext() {
            SecretEncryptionService service = service(KEY);
            String envelope = service.encrypt("prod-key", "production.apiKey");

            assertThat(service.decrypt(envelope, "production.apiKey")).isEqualTo("prod-key");
            // Moving the ciphertext into a different record's document must not decrypt.
            assertThatThrownBy(() -> service.decrypt(envelope, "staging.apiKey"))
                    .isInstanceOf(EncryptionException.class);
            assertThatThrownBy(() -> service.decrypt(envelope))
                    .isInstanceOf(EncryptionException.class);
        }

        @Test
        @DisplayName("refuses to operate with no key rather than storing plaintext")
        void refusesWithoutKey() {
            SecretEncryptionService service = service("");

            assertThat(service.isConfigured()).isFalse();
            assertThatThrownBy(() -> service.encrypt("anything"))
                    .isInstanceOf(EncryptionException.class)
                    .hasMessageContaining("No encryption key");
        }

        @Test
        @DisplayName("rejects a key that is not exactly 256 bits")
        void rejectsWrongKeyLength() {
            // Silently accepting a short key would give a system that looks like AES-256 but is not.
            assertThatThrownBy(() -> service(Base64.getEncoder().encodeToString(new byte[16])))
                    .isInstanceOf(com.orchpilot.workflow.auth.config.MissingSecurityKeyException.class)
                    .hasMessageContaining("exactly 32")
                    .hasMessageContaining("APP_ENCRYPTION_KEY");
        }

        @Test
        @DisplayName("reports a malformed envelope clearly")
        void rejectsMalformedEnvelope() {
            SecretEncryptionService service = service(KEY);

            assertThatThrownBy(() -> service.decrypt("not-an-envelope"))
                    .isInstanceOf(EncryptionException.class).hasMessageContaining("envelope");
            assertThatThrownBy(() -> service.decrypt("v9:test-key:AAAA:BBBB"))
                    .isInstanceOf(EncryptionException.class).hasMessageContaining("envelope");
            assertThatThrownBy(() -> service.decrypt("v1:other-key:AAAA:BBBB"))
                    .isInstanceOf(EncryptionException.class).hasMessageContaining("other-key");
        }
    }
}
