package com.orchpilot.workflow.auth.dto;

/**
 * Refresh request body, used only when the refresh token travels in the body rather than a cookie.
 *
 * <p>Optional by design: with the default cookie transport the browser supplies the token automatically
 * and the body is empty, so the field is nullable rather than validated.
 *
 * @param refreshToken the refresh token, or {@code null} when it arrives as a cookie
 */
public record RefreshTokenRequest(String refreshToken) {

    @Override
    public String toString() {
        return "RefreshTokenRequest{present=" + (refreshToken != null && !refreshToken.isBlank()) + "}";
    }
}
