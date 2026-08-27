package com.orchpilot.workflow.portability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The crypto guarantees the whole format rests on: authenticated encryption, so any change to the bytes is
 * detected; envelope key wrapping for platform mode; and password derivation that never yields the same key
 * for a different password or salt.
 */
class PackageCryptoTest {

    private final PackageCrypto crypto = new PackageCrypto();

    @Test
    @DisplayName("encrypt then decrypt returns the original bytes")
    void roundTrips() {
        byte[] key = crypto.newContentKey();
        byte[] nonce = crypto.newNonce();
        byte[] plaintext = "the workflow package".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = crypto.encrypt(key, nonce, plaintext);
        byte[] decrypted = crypto.decrypt(key, nonce, ciphertext);

        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext);
    }

    @Test
    @DisplayName("a flipped ciphertext byte is rejected by the auth tag")
    void detectsTampering() {
        byte[] key = crypto.newContentKey();
        byte[] nonce = crypto.newNonce();
        byte[] ciphertext = crypto.encrypt(key, nonce, "secret".getBytes(StandardCharsets.UTF_8));

        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThatThrownBy(() -> crypto.decrypt(key, nonce, ciphertext))
                .isInstanceOf(PackageIntegrityException.class);
    }

    @Test
    @DisplayName("decrypting with the wrong key fails, and the message does not say why")
    void wrongKeyFails() {
        byte[] nonce = crypto.newNonce();
        byte[] ciphertext = crypto.encrypt(crypto.newContentKey(), nonce,
                "secret".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> crypto.decrypt(crypto.newContentKey(), nonce, ciphertext))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageNotContainingAny("tag", "key", "AEAD", "GCM");
    }

    @Test
    @DisplayName("a content key wrapped under a master key unwraps back to itself")
    void wrapUnwrapRoundTrips() {
        byte[] master = crypto.newContentKey();
        byte[] contentKey = crypto.newContentKey();

        PackageCrypto.Sealed sealed = crypto.wrapKey(master, contentKey);
        byte[] unwrapped = crypto.unwrapKey(master, sealed.nonce(), sealed.ciphertext());

        assertThat(unwrapped).isEqualTo(contentKey);
    }

    @Test
    @DisplayName("unwrapping under a different master key fails")
    void unwrapWithWrongMasterFails() {
        byte[] contentKey = crypto.newContentKey();
        PackageCrypto.Sealed sealed = crypto.wrapKey(crypto.newContentKey(), contentKey);

        assertThatThrownBy(() -> crypto.unwrapKey(crypto.newContentKey(), sealed.nonce(), sealed.ciphertext()))
                .isInstanceOf(PackageIntegrityException.class);
    }

    @Test
    @DisplayName("the same password and salt derive the same key; a different password or salt does not")
    void deriveKeyIsDeterministicPerPasswordAndSalt() {
        byte[] salt = crypto.newSalt();
        byte[] a = derive("correct horse", salt);
        byte[] b = derive("correct horse", salt);
        byte[] differentPassword = derive("wrong horse", salt);
        byte[] differentSalt = derive("correct horse", crypto.newSalt());

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(32);
        assertThat(a).isNotEqualTo(differentPassword);
        assertThat(a).isNotEqualTo(differentSalt);
    }

    @Test
    @DisplayName("password mode: a wrong password derives a key that cannot decrypt")
    void wrongPasswordCannotDecrypt() {
        byte[] salt = crypto.newSalt();
        byte[] nonce = crypto.newNonce();
        byte[] key = derive("open sesame", salt);
        byte[] ciphertext = crypto.encrypt(key, nonce, "payload".getBytes(StandardCharsets.UTF_8));

        byte[] wrongKey = derive("open barley", salt);
        assertThatThrownBy(() -> crypto.decrypt(wrongKey, nonce, ciphertext))
                .isInstanceOf(PackageIntegrityException.class);
    }

    @Test
    @DisplayName("each export gets a fresh random key and nonce")
    void keysAndNoncesAreRandom() {
        assertThat(crypto.newContentKey()).isNotEqualTo(crypto.newContentKey());
        assertThat(crypto.newNonce()).hasSize(12).isNotEqualTo(crypto.newNonce());
        assertThat(crypto.newSalt()).hasSize(16).isNotEqualTo(crypto.newSalt());
    }

    private byte[] derive(String password, byte[] salt) {
        char[] chars = password.toCharArray();
        try {
            return crypto.deriveKey(chars, salt, PackageCrypto.ARGON2_ITERATIONS,
                    PackageCrypto.ARGON2_MEMORY_KB, PackageCrypto.ARGON2_PARALLELISM);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }
}
