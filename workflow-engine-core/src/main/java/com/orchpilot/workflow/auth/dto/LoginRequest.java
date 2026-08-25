package com.orchpilot.workflow.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login credentials.
 *
 * <p>{@code username} accepts either the username or the email address, because making a user remember
 * which one they signed up with is a support cost with no security benefit.
 *
 * <p>No password policy is applied here. A login must accept whatever the user has, including a password
 * that predates a policy change; validating complexity on the way in would lock out exactly the users
 * who most need to be told to change it.
 *
 * @param username username or email address
 * @param password raw password, verified against the stored hash and never logged or persisted
 */
public record LoginRequest(
        @NotBlank(message = "Username is required")
        @Size(max = 320, message = "Username is too long")
        String username,

        @NotBlank(message = "Password is required")
        @Size(max = 200, message = "Password is too long")
        String password) {

    /** Never render credentials, even by accident through a log statement or an error message. */
    @Override
    public String toString() {
        return "LoginRequest{username=" + username + "}";
    }
}
