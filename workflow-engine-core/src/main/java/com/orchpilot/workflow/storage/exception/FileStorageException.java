package com.orchpilot.workflow.storage.exception;

import com.orchpilot.workflow.exception.WorkflowEngineException;
import org.springframework.http.HttpStatus;

/**
 * A storage failure that maps to a meaningful API response.
 *
 * <h2>Why it carries its own status</h2>
 *
 * {@link WorkflowEngineException} is handled as a 500 by the global handler, which is right for an engine fault
 * but wrong for most of these: an unconfigured storage root is the administrator's problem (409), a missing file
 * is a 404, and a traversal attempt is a 400. Carrying the status on the exception keeps that decision next to
 * the reason instead of in a growing switch in the handler.
 *
 * <h2>Messages are safe to show</h2>
 *
 * No message produced here contains an absolute filesystem path. Telling a user that
 * {@code D:\OrchPilot\data\workflows\WF-1} is unwritable hands an attacker the deployment layout; telling them
 * the storage root is unwritable tells them what they need. The absolute path goes to the log, once, where an
 * operator can see it.
 */
public class FileStorageException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public FileStorageException(String errorCode, String message, HttpStatus status) {
        super(errorCode, message);
        this.status = status;
    }

    public FileStorageException(String errorCode, String message, HttpStatus status, Throwable cause) {
        super(errorCode, message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    // ------------------------------------------------------------------ the standard failures

    /** No settings document exists, or storage has been disabled. */
    public static FileStorageException notConfigured() {
        return new FileStorageException("FILE_STORAGE_NOT_CONFIGURED",
                "Workflow file storage has not been configured. Please contact an administrator.",
                HttpStatus.CONFLICT);
    }

    /** Configured, but the root is currently unusable — unmounted volume, changed permissions, deleted directory. */
    public static FileStorageException unavailable(String detail) {
        return new FileStorageException("FILE_STORAGE_UNAVAILABLE",
                "The configured storage location is not usable: " + detail
                        + " An administrator should re-test it in Settings → File Storage.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    public static FileStorageException fileNotFound(String fileId) {
        return new FileStorageException("FILE_NOT_FOUND",
                "No file '" + fileId + "' belongs to this workflow version.", HttpStatus.NOT_FOUND);
    }

    /**
     * The reference exists but the bytes do not.
     *
     * <p>Distinct from {@link #fileNotFound} on purpose: this one means the database and the storage disagree,
     * which is an operational fault worth investigating rather than a user asking for something that never existed.
     */
    public static FileStorageException missingFromStorage(String fileId) {
        return new FileStorageException("FILE_NOT_FOUND_IN_STORAGE",
                "File '" + fileId + "' is recorded in the database but is missing from storage. "
                        + "Run the storage consistency check.", HttpStatus.GONE);
    }

    public static FileStorageException rejected(String message) {
        return new FileStorageException("FILE_REJECTED", message, HttpStatus.BAD_REQUEST);
    }

    /** A resolved path escaped the storage root. Never expected from a legitimate caller. */
    public static FileStorageException pathEscape() {
        return new FileStorageException("FILE_PATH_INVALID",
                "The requested file path is not inside the configured storage location.",
                HttpStatus.BAD_REQUEST);
    }

    public static FileStorageException ioFailure(String operation, Throwable cause) {
        return new FileStorageException("FILE_STORAGE_IO_ERROR",
                "The storage location could not complete the " + operation + " operation.",
                HttpStatus.INTERNAL_SERVER_ERROR, cause);
    }

    /** A provider was requested for a storage type that has no implementation yet. */
    public static FileStorageException unsupportedProvider(String storageType) {
        return new FileStorageException("FILE_STORAGE_PROVIDER_UNSUPPORTED",
                "Storage type " + storageType + " is not available in this build.",
                HttpStatus.NOT_IMPLEMENTED);
    }
}
