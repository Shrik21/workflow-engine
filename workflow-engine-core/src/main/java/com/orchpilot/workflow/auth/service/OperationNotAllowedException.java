package com.orchpilot.workflow.auth.service;

import org.springframework.http.HttpStatus;

/**
 * A request that is well formed and authorised but would leave the platform in an unusable state.
 *
 * <p>Exists because these guards were previously {@link IllegalStateException}, which nothing mapped to a
 * status, so refusing to delete the last administrator answered 500. That is actively misleading: it reports
 * a server fault for a decision the server made deliberately, and it hides a message the caller needs to
 * read.
 *
 * <p>Carries its own status because the cases differ. Removing the last administrator is a conflict with
 * current state (409); self-registration being switched off is a policy refusal (403).
 *
 * <p>Messages here are written to be shown to the caller. They say what was refused and what to do instead,
 * and they disclose nothing beyond what the caller already knows.
 */
public class OperationNotAllowedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    private OperationNotAllowedException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * The requested change conflicts with the platform's current state.
     *
     * @param message what was refused and what to do instead
     * @return a 409 failure
     */
    public static OperationNotAllowedException conflict(String message) {
        return new OperationNotAllowedException(HttpStatus.CONFLICT, message);
    }

    /**
     * The operation is switched off by configuration.
     *
     * @param message what was refused
     * @return a 403 failure
     */
    public static OperationNotAllowedException forbidden(String message) {
        return new OperationNotAllowedException(HttpStatus.FORBIDDEN, message);
    }

    /** @return the status to answer with */
    public HttpStatus getStatus() {
        return status;
    }
}
