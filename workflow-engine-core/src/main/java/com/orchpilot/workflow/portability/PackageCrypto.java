package com.orchpilot.workflow.portability;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The cryptography behind a {@code .orchpilot} file: AES-256-GCM, with keys either wrapped under the platform
 * master key or derived from a password with Argon2id.
 *
 * <h2>Established algorithms only</h2>
 *
 * The file format is proprietary; the cryptography is not, and must not be. This is authenticated encryption —
 * AES-256-GCM — so tampering with the ciphertext is detected by the tag rather than reconstructed into a
 * plausible-but-altered workflow. There is no XOR, no base64-as-encryption, no reversible custom scheme, all of
 * which the specification names and forbids because they are encoding, not encryption.
 *
 * <h2>A fresh content key per export</h2>
 *
 * Every export encrypts its payload under a brand-new random 256-bit key and a fresh 96-bit nonce. In platform
 * mode that content key is then <em>wrapped</em> (encrypted) under the server's master key — envelope
 * encryption — and the wrapped key travels in the file's key metadata. In password mode the content key is
 * derived from the operator's password with Argon2id and never travels at all; only the salt and Argon2
 * parameters do, so the same password re-derives the same key on import. Neither the password nor any derived
 * key is ever stored.
 *
 * <h2>The wrap step is where a KMS slots in</h2>
 *
 * Platform mode calls one method to wrap the content key and one to unwrap it. Those two methods are the entire
 * surface a future AWS KMS / Azure Key Vault / GCP KMS / Vault integration would replace — the payload
 * encryption stays exactly as it is. No provider-specific code lives here.
 */
final class PackageCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;      // AES-256
    private static final int NONCE_BYTES = 12;    // 96-bit GCM nonce, the recommended size
    private static final int TAG_BITS = 128;      // full GCM tag

    /** Argon2id parameters. Deliberately the interactive-strength set: an export is an on-demand action. */
    static final int ARGON2_ITERATIONS = 3;
    static final int ARGON2_MEMORY_KB = 65_536;   // 64 MiB
    static final int ARGON2_PARALLELISM = 1;
    private static final int ARGON2_SALT_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    /** A content key and the nonce it was used with; the nonce is public, the key is not. */
    record Sealed(byte[] nonce, byte[] ciphertext) {
    }

    /** @return a fresh random 256-bit content key */
    byte[] newContentKey() {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        return key;
    }

    /** @return a fresh 96-bit nonce */
    byte[] newNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    /** @return a fresh Argon2 salt */
    byte[] newSalt() {
        byte[] salt = new byte[ARGON2_SALT_BYTES];
        random.nextBytes(salt);
        return salt;
    }

    /**
     * Encrypts a payload with AES-256-GCM.
     *
     * @param key       the 256-bit content key
     * @param nonce     the 96-bit nonce, unique for this key
     * @param plaintext the bytes to protect
     * @return the ciphertext, with the GCM tag appended by the provider
     */
    byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(plaintext);
        } catch (Exception ex) {
            throw new IllegalStateException("AES-GCM encryption failed", ex);
        }
    }

    /**
     * Decrypts and authenticates a payload.
     *
     * @param key        the 256-bit content key
     * @param nonce      the nonce it was encrypted with
     * @param ciphertext the ciphertext with its appended tag
     * @return the plaintext
     * @throws PackageIntegrityException when the tag does not verify — the file was tampered with, corrupted,
     *                                   or the key/password is wrong
     */
    byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException ex) {
            // The one exception a caller must distinguish: the ciphertext, the tag, the nonce or the key is
            // wrong. It is deliberately not said which, because to an attacker probing the difference between
            // "bad password" and "tampered file" is itself information.
            throw new PackageIntegrityException(
                    "The package could not be authenticated. It may be corrupted, tampered with, or — for a "
                            + "password-protected export — the password is wrong.", ex);
        } catch (Exception ex) {
            throw new PackageIntegrityException("The package could not be decrypted.", ex);
        }
    }

    /**
     * Wraps a content key under the platform master key.
     *
     * <p>This and {@link #unwrapKey} are the KMS seam: an integration replaces the two bodies and nothing
     * else changes. Today it is local AES-GCM under the configured master key.
     *
     * @param masterKey the platform master key, 128/192/256-bit
     * @param contentKey the content key to protect
     * @return the wrap nonce and the wrapped key
     */
    Sealed wrapKey(byte[] masterKey, byte[] contentKey) {
        byte[] nonce = newNonce();
        return new Sealed(nonce, encrypt(normaliseMaster(masterKey), nonce, contentKey));
    }

    /**
     * Unwraps a content key.
     *
     * @param masterKey the platform master key
     * @param wrapNonce the nonce the key was wrapped with
     * @param wrapped   the wrapped key
     * @return the content key
     * @throws PackageIntegrityException when the wrap does not authenticate — a file made for a different
     *                                   environment's master key
     */
    byte[] unwrapKey(byte[] masterKey, byte[] wrapNonce, byte[] wrapped) {
        try {
            return decrypt(normaliseMaster(masterKey), wrapNonce, wrapped);
        } catch (PackageIntegrityException ex) {
            throw new PackageIntegrityException(
                    "This package was not encrypted for this environment's platform key, so it cannot be "
                            + "imported here. Import it into the environment it was exported from, or re-export "
                            + "it with a password.", ex);
        }
    }

    /**
     * Derives a 256-bit key from a password with Argon2id.
     *
     * @param password   the export password
     * @param salt       the per-export salt
     * @param iterations Argon2 time cost
     * @param memoryKb   Argon2 memory cost
     * @param parallelism Argon2 lanes
     * @return the derived key
     */
    byte[] deriveKey(char[] password, byte[] salt, int iterations, int memoryKb, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(iterations)
                .withMemoryAsKB(memoryKb)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] key = new byte[KEY_BYTES];
        generator.generateBytes(password, key);
        return key;
    }

    /** AES needs a 16/24/32-byte key; a master key of the wrong length is a configuration error worth naming. */
    private static byte[] normaliseMaster(byte[] masterKey) {
        if (masterKey.length == 16 || masterKey.length == 24 || masterKey.length == 32) {
            return masterKey;
        }
        throw new IllegalStateException("The platform master key must be 128, 192 or 256 bits; it is "
                + (masterKey.length * 8) + " bits. Set a valid workflow.engine.secrets.master-key.");
    }

    /** Best-effort wipe of a key held in a mutable array, so it does not linger in the heap after use. */
    static void wipe(byte[] key) {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
        }
    }
}
