package com.orchpilot.workflow.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-registration.
 *
 * <p>There is deliberately no {@code roles} field. Public registration always produces a
 * {@link com.orchpilot.workflow.auth.model.Role#USER}, and the type makes privilege escalation impossible
 * rather than relying on the service to ignore a submitted value. A request body containing
 * {@code "roles": ["ADMIN"]} cannot bind to anything here.
 *
 * @param username  desired login name, normalised to lower case
 * @param email     email address, unique across accounts
 * @param password  raw password, validated against the configured policy then hashed with Argon2id
 * @param firstName given name
 * @param lastName  family name
 */
public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 64, message = "Username must be between 3 and 64 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "Username may contain letters, digits, dots, dashes and underscores only")
        String username,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 320, message = "Email is too long")
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 200, message = "Password is too long")
        String password,

        @Size(max = 100, message = "First name is too long")
        String firstName,

        @Size(max = 100, message = "Last name is too long")
        String lastName) {

    @Override
    public String toString() {
        return "RegisterRequest{username=" + username + ", email=" + email + "}";
    }
}
