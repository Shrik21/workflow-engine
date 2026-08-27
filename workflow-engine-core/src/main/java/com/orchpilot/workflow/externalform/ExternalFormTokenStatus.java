package com.orchpilot.workflow.externalform;

/**
 * The life of an external form access token.
 *
 * <p>A token is minted {@link #ACTIVE}. It leaves that state exactly once and never returns: a successful
 * submission that exhausts its allowance makes it {@link #USED}, an administrator makes it {@link #REVOKED}, and
 * the passage of its expiry makes it {@link #EXPIRED}. Only {@code ACTIVE} — and, for a multi-use token, one not
 * yet at its submission ceiling — may open, draft or submit.
 */
public enum ExternalFormTokenStatus {

    /** Usable, within its expiry and submission allowance. */
    ACTIVE,

    /** Its submission allowance is spent. Terminal. */
    USED,

    /** Withdrawn by an administrator. Terminal. */
    REVOKED,

    /** Past its expiry. Terminal. */
    EXPIRED
}
