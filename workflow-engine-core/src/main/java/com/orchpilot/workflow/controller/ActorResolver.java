package com.orchpilot.workflow.controller;

/**
 * Works out who to attribute an API call to.
 *
 * <p>The engine has no identity provider of its own; authentication is expected to be terminated by a gateway. This
 * reads the actor from a header so that audit records name a person rather than saying "api", and falls back to a
 * clearly non-authoritative value when the header is absent.
 *
 * <p>The value is used for attribution only and never for authorisation. Nothing in the engine grants access based
 * on it, because a client-supplied header cannot be trusted for that.
 */
final class ActorResolver {

    /** Header carrying the acting user, normally set by the authenticating gateway. */
    static final String ACTOR_HEADER = "X-Actor";

    private static final String UNKNOWN = "anonymous";

    private ActorResolver() {
    }

    /**
     * @param header raw header value, may be {@code null}
     * @return a non-blank actor name, truncated to a sensible length
     */
    static String resolve(String header) {
        if (header == null || header.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = header.trim();
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
    }
}
