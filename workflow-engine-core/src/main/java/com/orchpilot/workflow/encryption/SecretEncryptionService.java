package com.orchpilot.workflow.encryption;

/**
 * Reversible encryption for data that genuinely must be read back.
 *
 * <p>This exists for SendGrid API keys, OAuth client secrets, database passwords, bearer tokens and
 * workflow secret variables: values the platform has to present verbatim to a third party, and which
 * therefore cannot be hashed.
 *
 * <p><strong>User passwords must never pass through this interface.</strong> They are hashed with
 * Argon2id and the platform has no capability to recover them. The two mechanisms are separate types
 * with separate keys precisely so that "encrypt the password" is not an available move: there is no
 * method here that {@code AuthenticationService} calls, and no code path from a stored
 * {@code passwordHash} back to a password.
 *
 * <p>Implementations must use authenticated encryption. Confidentiality alone is not enough here:
 * an attacker with write access to MongoDB but no key could otherwise alter a ciphertext to redirect a
 * plugin at an endpoint of their choosing, and the application would decrypt it without complaint.
 */
public interface SecretEncryptionService {

    /**
     * Encrypts a value.
     *
     * @param plaintext value to protect; must not be {@code null}
     * @return a self-describing envelope safe to store as text, carrying the algorithm version, key id
     *         and nonce alongside the ciphertext
     * @throws EncryptionException when no key is configured or the operation fails
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a value produced by {@link #encrypt(String)}.
     *
     * @param envelope value previously returned by {@code encrypt}
     * @return the original plaintext
     * @throws EncryptionException when the envelope is malformed, was encrypted under an unavailable
     *                            key, or fails its authentication tag check
     */
    String decrypt(String envelope);

    /**
     * Encrypts a value, binding it to a context string.
     *
     * <p>The context is authenticated but not encrypted, and decryption fails unless the same context is
     * supplied. Passing the secret's name binds the ciphertext to its identity, so an attacker with
     * database write access cannot move the ciphertext of {@code staging.apiKey} into the
     * {@code production.apiKey} document and have it decrypt.
     *
     * @param plaintext value to protect
     * @param context   value to bind to, typically the record's stable identifier
     * @return a self-describing envelope
     * @throws EncryptionException when no key is configured or the operation fails
     */
    String encrypt(String plaintext, String context);

    /**
     * Decrypts a context-bound value.
     *
     * @param envelope value previously returned by {@link #encrypt(String, String)}
     * @param context  the same context used to encrypt
     * @return the original plaintext
     * @throws EncryptionException when the envelope is malformed or the context does not match
     */
    String decrypt(String envelope, String context);

    /**
     * @return whether an encryption key is configured; when false, every encrypt call fails and the
     *         platform refuses to store secrets rather than storing them unprotected
     */
    boolean isConfigured();

    /** @return identifier of the active key, recorded in envelopes so keys can be rotated */
    String activeKeyId();
}
