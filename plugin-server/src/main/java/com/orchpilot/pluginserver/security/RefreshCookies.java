package com.orchpilot.pluginserver.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The refresh cookie this registry issues, and nobody else's.
 *
 * <h2>Why the name matters more than it looks</h2>
 *
 * Cookies are scoped by host and path, and <b>not by port</b>. A console on {@code localhost:4300} and a
 * workflow console on {@code localhost:4200} therefore share one cookie jar, and both services put their
 * refresh cookie on {@code /api/auth}. The only thing keeping the two sessions apart is that they use
 * different names: this registry issues {@code plugin_registry_refresh}, the workflow platform issues
 * {@code workflow_refresh_token}. Each service reads and clears its own by name and never touches the other,
 * so signing in or out of one leaves the other exactly as it was.
 *
 * <p>That is a real constraint rather than a stylistic choice. Naming this cookie the same as the platform's
 * would make signing in to either application silently overwrite the other's session, which is the bug this
 * class exists to prevent.
 *
 * <h2>Why a cookie at all</h2>
 *
 * A refresh token is a week-long credential. In a cookie marked {@code HttpOnly} it is unreadable by script,
 * so an injected script cannot exfiltrate it; in {@code localStorage} it would be readable by anything running
 * on the page. The cookie is also what survives a page reload, which is what stops a refresh from signing the
 * operator out.
 *
 * <p>{@code SameSite=Strict} with a path of {@code /api/auth} means the browser sends it only on this
 * application's own requests to the endpoints that consume it. Combined with bearer-token authentication on
 * every other endpoint, there is nothing for a cross-site request to achieve, which is why CSRF protection is
 * not separately required.
 */
@Component
public class RefreshCookies {

    private final AuthProperties properties;

    public RefreshCookies(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * The cookie carrying a newly issued refresh token.
     *
     * @param token the refresh token
     * @return a {@code Set-Cookie} value
     */
    public String issue(String token) {
        AuthProperties.Cookie config = properties.getCookie();
        return ResponseCookie.from(config.getName(), token)
                .httpOnly(true)
                .secure(config.isSecure())
                .sameSite(config.getSameSite())
                .path(config.getPath())
                .maxAge(properties.getJwt().getRefreshTokenTtl())
                .build()
                .toString();
    }

    /**
     * The cookie that removes it.
     *
     * <p>Every attribute must match the one that set it, or the browser treats it as a different cookie and
     * leaves the original in place — a sign-out that appears to work and does not.
     *
     * @return a {@code Set-Cookie} value that expires immediately
     */
    public String clear() {
        AuthProperties.Cookie config = properties.getCookie();
        return ResponseCookie.from(config.getName(), "")
                .httpOnly(true)
                .secure(config.isSecure())
                .sameSite(config.getSameSite())
                .path(config.getPath())
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    /**
     * Reads this registry's refresh cookie from a request.
     *
     * <p>Matched by name, so the workflow platform's cookie arriving on the same request — which it will,
     * since they share a host and a path — is ignored rather than mistaken for this one.
     *
     * @param request the request
     * @return the token, or empty when the browser sent none of ours
     */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String name = properties.getCookie().getName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** @return the header name to write a cookie under */
    public String header() {
        return HttpHeaders.SET_COOKIE;
    }

    /** @return whether the refresh token travels in a cookie rather than the response body */
    public boolean isCookieTransport() {
        return properties.getCookie().isEnabled();
    }
}
