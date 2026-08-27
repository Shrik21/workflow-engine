package com.orchpilot.workflow.portability;

/**
 * A {@code .orchpilot} file failed a cryptographic or structural integrity check: a bad tag, a wrong master
 * key or password, a corrupted body, a bad magic header, an unsupported version.
 *
 * <p>Its message is deliberately non-specific about which check failed where that would tell an attacker
 * something — "bad password" versus "tampered file" is itself information.
 */
public class PackageIntegrityException extends RuntimeException {

    public PackageIntegrityException(String message) {
        super(message);
    }

    public PackageIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
