package com.orchpilot.workflow.auth.service;

import java.util.List;

/**
 * Thrown when a proposed password does not satisfy the configured policy.
 *
 * <p>Carries every violation rather than the first, so a user setting a password is told all of what is
 * wrong in one response instead of discovering the rules one rejection at a time.
 */
public class PasswordPolicyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> violations;

    public PasswordPolicyException(List<String> violations) {
        super("Password does not meet the required policy");
        this.violations = List.copyOf(violations);
    }

    /** @return every rule the proposed password broke, phrased for display */
    public List<String> getViolations() {
        return violations;
    }
}
