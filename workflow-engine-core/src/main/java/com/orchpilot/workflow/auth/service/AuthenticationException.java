package com.orchpilot.workflow.auth.service;

/**
 * Authentication failed.
 *
 * <p>Carries two messages, and the separation is the point. {@link #getMessage()} is the generic text the
 * client receives, always the same regardless of cause. {@link #getReason()} is the specific machine-readable
 * cause, which goes only to the audit trail.
 *
 * <p>Distinguishing "no such user" from "wrong password" from "account disabled" in a response turns the
 * login form into an oracle: an attacker learns which usernames exist and which accounts are worth
 * attacking, before guessing a single password. Every instance of this exception therefore renders as
 * {@code Invalid username or password}.
 */
public class AuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The only message a client ever sees for a failed login. */
    public static final String GENERIC_MESSAGE = "Invalid username or password";

    private final String reason;
    private final boolean lockedOut;

    private AuthenticationException(String message, String reason, boolean lockedOut) {
        super(message);
        this.reason = reason;
        this.lockedOut = lockedOut;
    }

    /**
     * @param reason specific cause, recorded in the audit trail and never returned to the client
     * @return a failure that renders generically
     */
    public static AuthenticationException invalidCredentials(String reason) {
        return new AuthenticationException(GENERIC_MESSAGE, reason, false);
    }

    /**
     * Too many failed attempts.
     *
     * <p>This one is not generic, and that is a deliberate exception to the rule. Telling the user their
     * account is temporarily locked is necessary for them to understand why a correct password stopped
     * working, and it discloses nothing an attacker cannot already infer from being throttled. It does not
     * reveal whether the account exists, because the throttle applies to any identifier.
     *
     * @param seconds how long the lockout lasts
     * @return a failure describing the lockout
     */
    public static AuthenticationException lockedOut(long seconds) {
        return new AuthenticationException(
                "Too many failed sign-in attempts. Try again in " + Math.max(1, seconds / 60) + " minute(s).",
                "rate_limited", true);
    }

    /**
     * @param reason specific cause
     * @return a failure indicating an invalid or expired refresh token
     */
    public static AuthenticationException invalidToken(String reason) {
        return new AuthenticationException("Your session has expired. Please sign in again.", reason, false);
    }

    /** @return the specific cause, for the audit trail only */
    public String getReason() {
        return reason;
    }

    /** @return whether this failure was produced by the throttle rather than by bad credentials */
    public boolean isLockedOut() {
        return lockedOut;
    }
}
