package com.orchpilot.workflow.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password change for the authenticated user.
 *
 * <p>The current password is required even though the caller already holds a valid access token. A token
 * may have been stolen, and re-proving knowledge of the password is what stops a stolen token from being
 * upgraded into permanent account control.
 *
 * @param currentPassword existing password, verified with {@code PasswordEncoder.matches}
 * @param newPassword     replacement, validated against the policy then hashed
 * @param confirmPassword must equal {@code newPassword}; checked on the server, not only in the browser
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 200)
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(max = 200)
        String newPassword,

        @NotBlank(message = "Password confirmation is required")
        @Size(max = 200)
        String confirmPassword) {

    /** @return whether the confirmation matches, checked server-side regardless of the client */
    public boolean isConfirmed() {
        return newPassword != null && newPassword.equals(confirmPassword);
    }

    @Override
    public String toString() {
        return "ChangePasswordRequest{}";
    }
}
