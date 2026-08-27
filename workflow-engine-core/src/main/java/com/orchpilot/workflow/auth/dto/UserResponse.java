package com.orchpilot.workflow.auth.dto;

import com.orchpilot.workflow.auth.model.Permission;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A user, as returned by the API.
 *
 * <p>There is no password field, and no field that could carry one. That is the point of having a
 * response type distinct from the persistence model: a future field added to {@code User} cannot leak
 * through this record, because mapping is explicit in {@link #from(User)}.
 *
 * <p>Permissions are included as well as roles so the console can decide what to render without
 * duplicating the role-to-permission table. The server still enforces every rule independently; this is
 * for the interface, not for authorization.
 *
 * @param id            user id
 * @param username      login name
 * @param email         email address
 * @param firstName     given name
 * @param lastName      family name
 * @param roles         assigned roles
 * @param permissions   permissions the roles grant, derived rather than stored
 * @param enabled       whether the account may authenticate
 * @param accountLocked whether an administrator has locked the account
 * @param createdAt     when the account was created
 * @param updatedAt     when it last changed
 * @param lastLoginAt   last successful authentication, or {@code null} if never
 */
public record UserResponse(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Set<Role> roles,
        Set<Permission> permissions,
        boolean enabled,
        boolean accountLocked,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt) {

    /**
     * @param user persistence model
     * @return the API representation, with every sensitive field omitted by construction
     */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                Set.copyOf(user.getRoles()),
                user.permissions(),
                user.isEnabled(),
                user.isAccountLocked(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt());
    }

    /**
     * @param users persistence models
     * @return API representations
     */
    public static List<UserResponse> from(List<User> users) {
        return users.stream().map(UserResponse::from).toList();
    }

    /** @return the user's display name, falling back to the username */
    public String displayName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? username : full;
    }
}
