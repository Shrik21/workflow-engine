package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.auth.model.Permission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Reads the authenticated principal from the security context.
 *
 * <p>A static accessor rather than injecting {@code Authentication} everywhere, because the callers that
 * need it are mostly deep in the service layer: workflow ownership checks, the execution context, and audit
 * attribution. Threading a parameter through every one of those signatures would be noise, and the
 * alternative of reading the context ad hoc in a dozen places is how inconsistent null handling appears.
 *
 * <p>Every method tolerates an unauthenticated context. Scheduled and event-triggered workflow executions
 * legitimately have no user, so absence is normal rather than exceptional.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @return the authenticated principal, or empty for an anonymous or system-initiated call
     */
    public static Optional<AuthPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /** @return the authenticated user's id, or empty */
    public static Optional<String> userId() {
        return principal().map(AuthPrincipal::getUserId);
    }

    /** @return the authenticated username, or empty */
    public static Optional<String> username() {
        return principal().map(AuthPrincipal::getUsername);
    }

    /**
     * @return the username for audit attribution, falling back to {@code system} for engine-initiated
     *         work such as a scheduled execution
     */
    public static String actorOrSystem() {
        return username().orElse("system");
    }

    /**
     * @param permission the permission to test
     * @return whether the current principal holds it; false when unauthenticated
     */
    public static boolean has(Permission permission) {
        return principal().map(p -> p.has(permission)).orElse(false);
    }

    /** @return whether the current principal is an administrator; false when unauthenticated */
    public static boolean isAdmin() {
        return principal().map(AuthPrincipal::isAdmin).orElse(false);
    }
}
