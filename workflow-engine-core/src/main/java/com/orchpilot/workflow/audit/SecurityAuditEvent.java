package com.orchpilot.workflow.audit;

/**
 * The security-relevant things that can happen, as a closed set.
 *
 * <p>An enum rather than a free-text label so that the audit trail is queryable and so that a caller
 * cannot invent an event name, or pass a credential where an event name was expected.
 */
public enum SecurityAuditEvent {

    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,

    /** A refresh token was exchanged for a new pair. */
    TOKEN_REFRESH,

    /**
     * An already-revoked refresh token was presented.
     *
     * <p>Treated as theft rather than as a mistake: the whole token family is revoked. This is the
     * event worth alerting on.
     */
    TOKEN_REUSE_DETECTED,

    PASSWORD_CHANGED,

    /** Login refused because the throttle had locked the identifier. */
    ACCOUNT_LOCKED,

    USER_REGISTERED,
    USER_CREATED,
    USER_UPDATED,
    USER_DELETED,
    USER_ENABLED,
    USER_DISABLED,
    USER_LOCKED,
    USER_UNLOCKED,
    ROLE_CHANGED,

    /** An authenticated principal was refused an endpoint by an authorization rule. */
    ACCESS_DENIED,

    // ------------------------------------------------------------------ groups
    // Every one of these changes who can reach what, so each is recorded with the actor, the subject and
    // the before-and-after state. A permission grant with no record of who made it is only ever noticed
    // when somebody asks how an account got access.
    GROUP_CREATED,
    GROUP_UPDATED,
    GROUP_DELETED,
    GROUP_MEMBER_ADDED,
    GROUP_MEMBER_REMOVED,
    GROUP_PERMISSION_UPDATED,
    WORKFLOW_GROUP_ATTACHED,
    WORKFLOW_GROUP_REMOVED,

    PLUGIN_PERMISSION_CHANGED
}
