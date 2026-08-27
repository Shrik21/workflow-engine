package com.orchpilot.workflow.auth.service;

/**
 * A username or email address is already taken.
 *
 * <p>Reported plainly, unlike a login failure. Registration inherently discloses whether an identifier is
 * available, because a form that accepted a duplicate and then silently failed would be unusable. The
 * mitigation for the enumeration this permits belongs elsewhere: rate limiting on registration, and not
 * disclosing the same fact on the login path.
 */
public class DuplicateAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    /**
     * @param field   which identifier collided, {@code username} or {@code email}
     * @param message human-readable message
     */
    public DuplicateAccountException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** @return the colliding field, so the console can mark the right input */
    public String getField() {
        return field;
    }
}
