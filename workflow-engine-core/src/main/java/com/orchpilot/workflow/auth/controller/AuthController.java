package com.orchpilot.workflow.auth.controller;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.dto.ChangePasswordRequest;
import com.orchpilot.workflow.auth.dto.LoginRequest;
import com.orchpilot.workflow.auth.dto.LoginResponse;
import com.orchpilot.workflow.auth.dto.RefreshTokenRequest;
import com.orchpilot.workflow.auth.dto.RegisterRequest;
import com.orchpilot.workflow.auth.dto.UserResponse;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.RefreshTokenCookies;
import com.orchpilot.workflow.auth.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Authentication endpoints.
 *
 * <p>The controller's job is transport: read the refresh token from wherever it lives, hand it to the
 * service, and place the new one back. It contains no security decisions, which is why every method is
 * short. The service does not know cookies exist and this class does not know how a token is minted.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh, logout and password change")
public class AuthController {

    private final AuthenticationService authentication;
    private final RefreshTokenCookies cookies;
    private final AuthProperties properties;

    public AuthController(AuthenticationService authentication, RefreshTokenCookies cookies,
                          AuthProperties properties) {
        this.authentication = authentication;
        this.cookies = cookies;
        this.properties = properties;
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account",
            description = "Always creates a USER. There is no way to request a role: the request type has "
                    + "no roles field, so privilege escalation through registration is impossible rather "
                    + "than merely disallowed. Only an administrator can grant ADMIN.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "409", description = "Username or email already taken")
    @ApiResponse(responseCode = "422", description = "Password does not meet the policy")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        UserResponse created = authentication.register(request, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in",
            description = "Returns a short-lived access token. The refresh token is set as an HttpOnly "
                    + "SameSite=Strict cookie by default, so it is unreadable by script; it appears in the "
                    + "response body only when security.jwt.refresh-token-transport is 'body'. Every "
                    + "failure answers 401 with the same generic message, whatever the cause.")
    @ApiResponse(responseCode = "200", description = "Authenticated")
    @ApiResponse(responseCode = "401", description = "Invalid credentials, or too many failed attempts")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        AuthenticationService.AuthResult result = authentication.login(request, httpRequest);
        return withRefreshCookie(result, httpRequest);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token",
            description = "Rotates the refresh token: the presented one is revoked and a new one issued. "
                    + "Presenting an already-revoked token is treated as theft, revokes the whole token "
                    + "family and forces a fresh sign-in.")
    @ApiResponse(responseCode = "200", description = "New token pair issued")
    @ApiResponse(responseCode = "401", description = "Token missing, expired, unknown or already used")
    public ResponseEntity<LoginResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest) {
        String presented = cookies.read(httpRequest, body == null ? null : body.refreshToken()).orElse(null);
        AuthenticationService.AuthResult result = authentication.refresh(presented, httpRequest);
        return withRefreshCookie(result, httpRequest);
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out",
            description = "Revokes the refresh token and clears the cookie. Idempotent: an unknown or "
                    + "already-expired token still answers 204, because a client must always be able to "
                    + "clear its own state.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshTokenRequest body,
                                       HttpServletRequest httpRequest) {
        cookies.read(httpRequest, body == null ? null : body.refreshToken())
                .ifPresent(token -> authentication.logout(token, httpRequest));

        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        if (cookies.isCookieTransport()) {
            response = response.header(cookies.headerName(), cookies.clear(httpRequest));
        }
        return response.build();
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in user",
            description = "Read from the database rather than from the token, so a role change or a "
                    + "disabled account is reflected immediately.")
    @ApiResponse(responseCode = "200", description = "The current user")
    @ApiResponse(responseCode = "401", description = "Not authenticated")
    public UserResponse me(@AuthenticationPrincipal AuthPrincipal principal) {
        return authentication.currentUser(principal.getUserId());
    }

    @PostMapping("/change-password")
    @Operation(summary = "Change your password",
            description = "Requires the current password even though the caller is already authenticated, so "
                    + "that a stolen access token cannot be turned into permanent account control. Every "
                    + "session is revoked afterwards.")
    @ApiResponse(responseCode = "204", description = "Password changed; sign in again")
    @ApiResponse(responseCode = "401", description = "Current password incorrect")
    @ApiResponse(responseCode = "422", description = "New password does not meet the policy")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                                              @Valid @RequestBody ChangePasswordRequest request,
                                              HttpServletRequest httpRequest) {
        authentication.changePassword(principal.getUserId(), request, httpRequest);

        // The cookie is cleared because every session was just revoked, including this one. Leaving a
        // dead cookie in place would make the next refresh fail confusingly instead of cleanly.
        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        if (cookies.isCookieTransport()) {
            response = response.header(cookies.headerName(), cookies.clear(httpRequest));
        }
        return response.build();
    }

    @GetMapping("/password-policy")
    @Operation(summary = "The password rules",
            description = "Lets the registration form show the rules actually in force instead of "
                    + "hardcoding a guess at them. Public, because it is needed before sign-in and "
                    + "discloses only policy.")
    public Map<String, Object> passwordPolicy() {
        AuthProperties.Password policy = properties.getPassword();
        return Map.of(
                "minLength", policy.getMinLength(),
                "maxLength", policy.getMaxLength(),
                "requireUppercase", policy.isRequireUppercase(),
                "requireLowercase", policy.isRequireLowercase(),
                "requireDigit", policy.isRequireDigit(),
                "requireSpecial", policy.isRequireSpecial(),
                "registrationEnabled", properties.getRegistration().isEnabled(),
                "rules", describe(policy));
    }

    /**
     * Attaches the rotated refresh token, as a cookie or in the body.
     *
     * <p>{@code Cache-Control: no-store} on both paths: a token response must never be written to a shared
     * cache or to the browser's disk cache.
     */
    private ResponseEntity<LoginResponse> withRefreshCookie(AuthenticationService.AuthResult result,
                                                            HttpServletRequest httpRequest) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().header("Cache-Control", "no-store");
        if (cookies.isCookieTransport()) {
            builder = builder.header(cookies.headerName(),
                    cookies.issue(result.rawRefreshToken(), httpRequest));
        }
        return builder.body(result.response());
    }

    private static List<String> describe(AuthProperties.Password policy) {
        List<String> rules = new java.util.ArrayList<>();
        rules.add("At least " + policy.getMinLength() + " characters");
        if (policy.isRequireUppercase()) {
            rules.add("An upper-case letter");
        }
        if (policy.isRequireLowercase()) {
            rules.add("A lower-case letter");
        }
        if (policy.isRequireDigit()) {
            rules.add("A digit");
        }
        if (policy.isRequireSpecial()) {
            rules.add("A special character");
        }
        rules.add("Not a commonly used password");
        return rules;
    }
}
