package com.orchpilot.pluginserver.auth;

import com.orchpilot.pluginserver.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What the authentication endpoints accept and return.
 *
 * <p>Records, so every one of them is immutable and cannot accidentally carry a field back that it was not
 * meant to. That matters most for {@link UserView}: it is the shape a user is described in everywhere, and it
 * has no field a password hash could be assigned to even by mistake.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** Sign-in. */
    public record LoginRequest(
            @NotBlank(message = "A username is required") String username,
            @NotBlank(message = "A password is required") String password) {
    }

    /**
     * Renewal.
     *
     * <p>Optional, because a browser normally sends the token in this registry's {@code HttpOnly} cookie and
     * has no way to read it back out to put in a body. A non-browser client that holds its own token supplies
     * it here instead.
     */
    public record RefreshRequest(String refreshToken) {
    }

    /** Sign-out. The token is optional for the same reason, and a request without one still clears the cookie. */
    public record LogoutRequest(String refreshToken) {
    }

    /** Self-registration, when this registry allows it at all. */
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Email(message = "A valid email address is required") String email,
            @NotBlank String password,
            String firstName,
            String lastName) {
    }

    /** Changing one's own password. */
    public record ChangePasswordRequest(
            @NotBlank(message = "Your current password is required") String currentPassword,
            @NotBlank(message = "A new password is required") String newPassword) {
    }

    /**
     * A signed-in session.
     *
     * @param accessToken        short-lived, carries roles and permissions
     * @param refreshToken       rotates on every use; this is the only time its value is ever readable
     * @param tokenType          always {@code Bearer}
     * @param expiresIn          access-token lifetime in seconds
     * @param mustChangePassword whether the holder must replace their password before doing anything else
     * @param user               who signed in
     */
    public record SessionResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                  boolean mustChangePassword, UserView user) {

        public static SessionResponse of(AuthService.Session session) {
            return new SessionResponse(session.accessToken(), session.refreshToken(), "Bearer",
                    session.expiresIn(), session.mustChangePassword(),
                    UserView.of(session.user(), session.permissions()));
        }

        /**
         * The same session with the refresh token withheld from the body.
         *
         * <p>Used when the token travels in an {@code HttpOnly} cookie. Returning it in the body as well
         * would hand it back to script and undo the entire reason for the cookie.
         *
         * @param session the session
         * @return the response a browser client receives
         */
        public static SessionResponse withoutRefreshToken(AuthService.Session session) {
            return new SessionResponse(session.accessToken(), null, "Bearer", session.expiresIn(),
                    session.mustChangePassword(), UserView.of(session.user(), session.permissions()));
        }
    }

    /**
     * A user, as every endpoint describes one.
     *
     * <p>There is no password field, and no field a hash could be put in. The type makes leaking one a
     * compile error rather than a code-review catch.
     */
    public record UserView(String id, String username, String email, String firstName, String lastName,
                           String displayName, Set<String> roles, Set<String> permissions, boolean enabled,
                           boolean accountLocked, boolean serviceAccount, boolean mustChangePassword,
                           Instant createdAt, Instant updatedAt, Instant lastLoginAt) {

        public static UserView of(User user, Set<String> permissions) {
            return new UserView(user.getId(), user.getUsername(), user.getEmail(), user.getFirstName(),
                    user.getLastName(), user.displayName(), user.getRoles(), permissions, user.isEnabled(),
                    user.isCurrentlyLocked(), user.isServiceAccount(), user.isMustChangePassword(),
                    user.getCreatedAt(), user.getUpdatedAt(), user.getLastLoginAt());
        }
    }

    /**
     * What a password must satisfy, so a form can say so before anything is typed.
     *
     * @param rules               the requirements, in words
     * @param minLength           the length rule, separately, so a field can count as somebody types
     * @param registrationEnabled whether this registry accepts self-registration
     */
    public record PasswordPolicyResponse(List<String> rules, int minLength, boolean registrationEnabled) {
    }
}
