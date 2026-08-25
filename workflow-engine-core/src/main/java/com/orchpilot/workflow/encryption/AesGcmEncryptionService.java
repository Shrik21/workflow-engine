package com.orchpilot.workflow.encryption;

import com.orchpilot.workflow.auth.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM implementation of {@link SecretEncryptionService}.
 *
 * <p>Design decisions worth stating, because each of them is a way this goes wrong:
 *
 * <ul>
 *   <li><b>GCM, not CBC.</b> GCM is authenticated: tampering with a ciphertext fails the tag check
 *       rather than producing plausible garbage. With CBC an attacker holding database write access can
 *       flip bits to alter a decrypted URL or token without the application noticing.</li>
 *   <li><b>A fresh 96-bit nonce per encryption, from {@link SecureRandom}.</b> Nonce reuse under the
 *       same key is catastrophic for GCM: two ciphertexts under one nonce leak the XOR of their
 *       plaintexts and, worse, allow forgery of the authentication tag. The nonce is generated per call
 *       and travels in the envelope, so it is never derived from anything reusable.</li>
 *   <li><b>A self-describing envelope</b> of {@code v1:keyId:nonce:ciphertext}. Carrying the key id
 *       means a key can be rotated and old values still read; carrying the version means the format can
 *       change without a migration guess.</li>
 *   <li><b>No custom cryptography.</b> This is JDK AES-GCM with standard parameters. Nothing here
 *       invents a construction.</li>
 * </ul>
 *
 * <p>The key comes from {@code security.encryption.key} and therefore from the environment. When it is
 * absent, {@link #isConfigured()} is false and every encrypt call throws: refusing to store a secret is
 * correct, storing it unprotected is not.
 */
@Service
public class AesGcmEncryptionService implements SecretEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(AesGcmEncryptionService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final String ENVELOPE_VERSION = "v1";
    private static final String SEPARATOR = ":";

    /** 96 bits is the GCM-recommended nonce size and the only one with a proven security bound. */
    private static final int NONCE_BYTES = 12;

    /** Full 128-bit authentication tag. Truncating it weakens forgery resistance for no real saving. */
    private static final int TAG_BITS = 128;

    private static final int AES_256_KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final String keyId;

    public AesGcmEncryptionService(AuthProperties properties) {
        this.keyId = properties.getEncryption().getKeyId();
        this.key = loadKey(properties.getEncryption().getKey());
        if (this.key == null) {
            log.warn("No encryption key configured (security.encryption.key). Storing secrets is "
                    + "disabled until one is supplied. Generate one with: openssl rand -base64 32");
        }
    }

    @Override
    public String encrypt(String plaintext) {
        return encrypt(plaintext, null);
    }

    @Override
    public String decrypt(String envelope) {
        return decrypt(envelope, null);
    }

    @Override
    public String encrypt(String plaintext, String context) {
        if (plaintext == null) {
            throw new EncryptionException("Cannot encrypt a null value");
        }
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            if (context != null && !context.isEmpty()) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return String.join(SEPARATOR,
                    ENVELOPE_VERSION, keyId, encoder.encodeToString(nonce), encoder.encodeToString(cipherText));
        } catch (GeneralSecurityException ex) {
            // Deliberately does not include the value or any part of it.
            throw new EncryptionException("Encryption failed: " + ex.getClass().getSimpleName(), ex);
        }
    }

    @Override
    public String decrypt(String envelope, String context) {
        if (envelope == null || envelope.isBlank()) {
            throw new EncryptionException("Cannot decrypt an empty value");
        }
        requireKey();

        // Limit of 4: the ciphertext segment is Base64 and contains no separator, so a bounded split
        // keeps a malformed envelope from being silently reinterpreted.
        String[] parts = envelope.split(SEPARATOR, 4);
        if (parts.length != 4 || !ENVELOPE_VERSION.equals(parts[0])) {
            throw new EncryptionException("Malformed encrypted value: unrecognised envelope format");
        }
        if (!keyId.equals(parts[1])) {
            // A clear, actionable message: the value is intact but was written under a different key.
            throw new EncryptionException("Value was encrypted with key '" + parts[1]
                    + "' but the active key is '" + keyId + "'. Restore that key to read it.");
        }

        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] nonce = decoder.decode(parts[2]);
            byte[] cipherText = decoder.decode(parts[3]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            if (context != null && !context.isEmpty()) {
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new EncryptionException("Malformed encrypted value: invalid Base64", ex);
        } catch (GeneralSecurityException ex) {
            // Covers a failed tag check, which means either the wrong context or actual tampering.
            throw new EncryptionException(
                    "Decryption failed: the value has been altered or the context does not match", ex);
        }
    }

    @Override
    public boolean isConfigured() {
        return key != null;
    }

    @Override
    public String activeKeyId() {
        return keyId;
    }

    private void requireKey() {
        if (key == null) {
            throw new EncryptionException(
                    "No encryption key is configured. Set security.encryption.key (APP_ENCRYPTION_KEY).");
        }
    }

    /**
     * Loads the Base64 key, insisting on a full 256 bits.
     *
     * <p>A short key is rejected rather than padded or stretched. Silently accepting 8 bytes would give
     * a system that appears to use AES-256 while offering 64 bits of security.
     */
    private static SecretKey loadKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new com.orchpilot.workflow.auth.config.MissingSecurityKeyException(
                    "security.encryption.key", "APP_ENCRYPTION_KEY", "openssl rand -base64 32",
                    "The secret encryption key is not valid Base64.");
        }
        if (raw.length != AES_256_KEY_BYTES) {
            throw new com.orchpilot.workflow.auth.config.MissingSecurityKeyException(
                    "security.encryption.key", "APP_ENCRYPTION_KEY", "openssl rand -base64 32",
                    "The secret encryption key decodes to " + raw.length + " bytes, but AES-256 requires "
                            + "exactly " + AES_256_KEY_BYTES + ". Accepting a shorter key would produce a "
                            + "system that looks like AES-256 without providing its strength.");
        }
        return new SecretKeySpec(raw, ALGORITHM);
    }
}
