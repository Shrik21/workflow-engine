package com.orchpilot.workflow.sdk.node;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The user an execution is running on behalf of, as much of them as a plugin is allowed to see.
 *
 * <p>Three fields, and the omissions are the design. There is no access token, no refresh token, no
 * password hash and no email address, because this type has no field that could carry one. A plugin
 * therefore cannot borrow the user's credentials to call an API as them, cannot exfiltrate a token, and
 * cannot learn a personal identifier it was not given: not because it is asked not to, but because the
 * value it receives does not contain them.
 *
 * <p>What it can do is attribute its work. An email plugin can set a reply-to, an approval plugin can
 * record who triggered it, and an audit-conscious plugin can log a user id. Roles are included so a plugin
 * can vary behaviour for administrators, though authorization decisions belong to the engine and a plugin
 * should not be the only thing enforcing one.
 *
 * <p>Absent for scheduled and event-triggered executions, which have no user. Plugins must handle that:
 * {@link NodeExecutionContext#currentUser()} returns an {@code Optional} for exactly that reason.
 *
 * @since 1.0.0
 */
public final class WorkflowUser {

    private final String userId;
    private final String username;
    private final Set<String> roles;

    /**
     * @param userId   stable user identifier
     * @param username login name
     * @param roles    role names; {@code null} is treated as empty
     */
    public WorkflowUser(String userId, String username, Set<String> roles) {
        this.userId = userId;
        this.username = username;
        this.roles = roles == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }

    /** @return the stable user identifier, suitable for recording against work the plugin performs */
    public String userId() {
        return userId;
    }

    /** @return the login name, suitable for display */
    public String username() {
        return username;
    }

    /** @return unmodifiable role names */
    public Set<String> roles() {
        return roles;
    }

    /**
     * @param role role name, matched case-insensitively and ignoring any {@code ROLE_} prefix
     * @return whether the user holds it
     */
    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        String wanted = role.trim().toUpperCase(java.util.Locale.ROOT);
        String bare = wanted.startsWith("ROLE_") ? wanted.substring(5) : wanted;
        return roles.stream()
                .map(held -> held.toUpperCase(java.util.Locale.ROOT))
                .anyMatch(held -> held.equals(bare) || held.equals("ROLE_" + bare));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof WorkflowUser other && Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    @Override
    public String toString() {
        return "WorkflowUser{userId=" + userId + ", username=" + username + ", roles=" + roles + "}";
    }
}
