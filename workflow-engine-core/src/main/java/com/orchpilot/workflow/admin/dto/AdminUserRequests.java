package com.orchpilot.workflow.admin.dto;

import com.orchpilot.workflow.auth.model.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Request payloads for user administration.
 *
 * <p>Grouped in one file because they are small, always change together, and are only ever used by
 * {@code UserAdminController}. Splitting four short records across four files would spread one cohesive
 * contract over a directory.
 *
 * <p>Unlike self-registration, these types <em>do</em> carry roles: assigning a role is exactly what an
 * administrator is for. The authorization that makes that safe lives in the security configuration and on
 * the controller, not in the shape of the payload.
 */
public final class AdminUserRequests {

    private AdminUserRequests() {
    }

    /**
     * Creates an account on a user's behalf.
     *
     * @param username  login name
     * @param email     email address
     * @param password  initial password, validated against the policy then hashed
     * @param firstName given name
     * @param lastName  family name
     * @param roles     roles to assign; defaults to USER when empty
     * @param enabled   whether the account may sign in immediately
     */
    public record CreateUser(
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 64, message = "Username must be between 3 and 64 characters")
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "Username may contain letters, digits, dots, dashes and underscores only")
            String username,

            @NotBlank(message = "Email is required")
            @Email(message = "Email must be a valid address")
            String email,

            @NotBlank(message = "An initial password is required")
            @Size(max = 200)
            String password,

            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,

            Set<Role> roles,
            Boolean enabled) {

        /** Never render the password, even accidentally through a log statement. */
        @Override
        public String toString() {
            return "CreateUser{username=" + username + ", email=" + email + ", roles=" + roles + "}";
        }
    }

    /**
     * Updates a profile. Deliberately cannot change a password or a role: those have their own endpoints so
     * each is separately authorised and separately audited.
     *
     * @param email     new email address
     * @param firstName new given name
     * @param lastName  new family name
     */
    public record UpdateUser(
            @Email(message = "Email must be a valid address") String email,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName) {
    }

    /**
     * Replaces a user's roles.
     *
     * <p>A replacement rather than an add or remove, so the caller states the intended final state and two
     * concurrent edits cannot combine into something neither administrator chose.
     *
     * @param roles the complete new role set; must not be empty
     */
    public record UpdateRoles(
            @NotEmpty(message = "At least one role is required")
            Set<Role> roles) {
    }

    /**
     * Enables or disables an account.
     *
     * @param enabled whether the account may authenticate
     * @param reason  optional note recorded in the audit trail
     */
    public record UpdateStatus(
            boolean enabled,
            @Size(max = 500) String reason) {
    }
}
