package com.orchpilot.workflow.encryption;

/**
 * Thrown when encryption or decryption cannot be performed.
 *
 * <p>Messages describe the failure category only, never the value involved. A message such as "failed to
 * decrypt sendgrid.apiKey" is useful; one that echoes a ciphertext or a partially decrypted value is a
 * disclosure, and error responses derived from these exceptions are deliberately generic.
 */
public class EncryptionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
