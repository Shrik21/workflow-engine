package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.auth.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Places and clears the refresh-token cookie.
 *
 * <p>Every attribute here is doing security work:
 *
 * <ul>
 *   <li><b>HttpOnly</b> so injected script cannot read the token. This is the whole reason to prefer a
 *       cookie over {@code localStorage}: an XSS flaw can call the API as the user, but it cannot exfiltrate
 *       a credential that outlives the page.</li>
 *   <li><b>SameSite=Strict</b> so a browser will not attach it to a cross-site request. That is what makes
 *       disabling CSRF tokens defensible for this cookie.</li>
 *   <li><b>Path=/api/auth</b> so it is not sent on ordinary API calls at all. Narrowing the path limits both
 *       accidental exposure in logs and the reach of anything that does manage to trigger a request.</li>
 *   <li><b>Secure</b> whenever the request arrived over HTTPS, or whenever configuration forces it. Set
 *       automatically rather than hardcoded so a plain-HTTP local setup works without making production
 *       insecure.</li>
 * </ul>
 */
@Component
public class RefreshTokenCookies {

    private final AuthProperties.Jwt properties;

    public RefreshTokenCookies(AuthProperties properties) {
        this.properties = properties.getJwt();
    }

    /** @return whether the refresh token is carried as a cookie rather than in the response body */
    public boolean isCookieTransport() {
        return properties.isCookieTransport();
    }

    /**
     * Builds the {@code Set-Cookie} header value carrying a new token.
     *
     * @param rawToken the refresh token
     * @param request  current request, used to decide whether Secure applies
     * @return the header value
     */
    public String issue(String rawToken, HttpServletRequest request) {
        return ResponseCookie.from(properties.getCookieName(), rawToken)
                .httpOnly(true)
                .secure(isSecure(request))
                .path(properties.getCookiePath())
                .maxAge(Duration.ofMillis(properties.getRefreshTokenExpiration()))
                .sameSite(properties.getCookieSameSite())
                .build()
                .toString();
    }

    /**
     * Builds the header value that removes the cookie.
     *
     * <p>Attributes must match the ones used to set it, or the browser treats it as a different cookie and
     * leaves the original in place. That is the usual reason a logout appears not to work.
     *
     * @param request current request
     * @return the header value
     */
    public String clear(HttpServletRequest request) {
        return ResponseCookie.from(properties.getCookieName(), "")
                .httpOnly(true)
                .secure(isSecure(request))
                .path(properties.getCookiePath())
                .maxAge(Duration.ZERO)
                .sameSite(properties.getCookieSameSite())
                .build()
                .toString();
    }

    /**
     * Reads the token the client presented.
     *
     * @param request  current request
     * @param fromBody token supplied in the request body, for the body transport
     * @return the token, preferring the cookie
     */
    public Optional<String> read(HttpServletRequest request, String fromBody) {
        Optional<String> fromCookie = cookie(request);
        if (fromCookie.isPresent()) {
            return fromCookie;
        }
        return Optional.ofNullable(fromBody).filter(value -> !value.isBlank());
    }

    /** @return the header name to set, for a controller building a response */
    public String headerName() {
        return HttpHeaders.SET_COOKIE;
    }

    private Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request == null ? null : request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (properties.getCookieName().equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Secure when configuration demands it, when the request arrived over TLS, or when a proxy says it
     * did. The forwarded header is honoured because the engine normally terminates TLS at a reverse proxy
     * and would otherwise see plain HTTP and omit the attribute in production.
     */
    private boolean isSecure(HttpServletRequest request) {
        if (properties.isCookieSecure()) {
            return true;
        }
        if (request == null) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        return "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
