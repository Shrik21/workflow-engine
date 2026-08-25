package com.orchpilot.workflow.auth.dto;

/**
 * A successful authentication.
 *
 * <p>{@code refreshToken} is populated only when the configured transport is {@code body}. With the
 * default cookie transport it is {@code null} here and the token is set as an HttpOnly cookie instead,
 * which keeps it out of reach of any script running on the page.
 *
 * @param accessToken  JWT to send as {@code Authorization: Bearer}
 * @param refreshToken refresh token, or {@code null} when it was returned as a cookie
 * @param tokenType    always {@code Bearer}
 * @param expiresIn    access token lifetime in seconds, so the client can refresh before it expires
 * @param user         the authenticated user, saving the console an immediate follow-up request
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserResponse user) {

    /** Token type every response uses. */
    public static final String BEARER = "Bearer";

    /**
     * @param accessToken     signed access token
     * @param refreshToken    refresh token, or {@code null} when carried as a cookie
     * @param expiresInSecond access token lifetime in seconds
     * @param user            authenticated user
     * @return the response
     */
    public static LoginResponse of(String accessToken, String refreshToken, long expiresInSecond,
                                   UserResponse user) {
        return new LoginResponse(accessToken, refreshToken, BEARER, expiresInSecond, user);
    }

    /** Never log a token. */
    @Override
    public String toString() {
        return "LoginResponse{user=" + (user == null ? null : user.username()) + ", expiresIn=" + expiresIn + "}";
    }
}
