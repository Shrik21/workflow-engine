package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Turns a bearer token into an authenticated security context.
 *
 * <pre>
 * Authorization: Bearer &lt;jwt&gt;
 *      → validate signature, algorithm, issuer, expiry
 *      → load the user by the subject claim
 *      → check the account is still usable
 *      → authorities from current roles
 *      → SecurityContext
 * </pre>
 *
 * <p><b>Why the database is consulted on every request.</b> The token's own {@code roles} claim is ignored
 * as an authority source. Trusting it would mean a disabled account, a revoked role or a deleted user kept
 * working until the token expired, which for a 15-minute token is a 15-minute window during which an
 * administrator's revocation does nothing. One indexed lookup by primary key is a small price for
 * revocation that takes effect immediately, and this engine already talks to MongoDB on essentially every
 * request. If that ever became a bottleneck, the fix is a short-TTL cache keyed on user id, not trusting
 * the claim.
 *
 * <p>A missing or invalid token is not an error here. The filter simply leaves the context unauthenticated
 * and lets the authorization layer decide, which is what allows public endpoints to work and produces a
 * consistent 401 from a single place rather than from the middle of a filter.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, CustomUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // A CORS preflight carries no Authorization header by definition, so there is nothing to do.
        return CorsUtils.isPreFlightRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Never overwrite an existing authentication: another mechanism may have established it, and
        // silently replacing it would make the effective identity depend on filter order.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<String> token = extractToken(request);
        if (token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        jwtService.parse(token.get())
                .flatMap(identity -> userDetailsService.findById(identity.userId()))
                .filter(this::isUsable)
                .ifPresent(user -> authenticate(user, request));

        chain.doFilter(request, response);
    }

    private boolean isUsable(User user) {
        if (user.isUsable()) {
            return true;
        }
        // A token that is cryptographically valid for an account that has since been disabled. Worth a
        // log line, because it is the visible half of an administrative action taking effect.
        log.debug("Rejecting a valid token for unusable account {} (enabled={}, locked={}, expired={})",
                user.getId(), user.isEnabled(), user.isAccountLocked(), user.isAccountExpired());
        return false;
    }

    private void authenticate(User user, HttpServletRequest request) {
        AuthPrincipal principal = AuthPrincipal.of(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(detailsSource.buildDetails(request));

        // A fresh context rather than mutating the existing one, which is what Spring Security 6 and
        // later expect and what keeps the context from leaking between pooled request threads.
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * Reads the bearer token.
     *
     * <p>Only the {@code Authorization} header is accepted. Tokens are deliberately not read from a query
     * parameter: URLs are logged by proxies, stored in browser history and sent in referrer headers, which
     * makes a token in a query string a credential written to several places nobody audits.
     */
    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String value = header.substring(BEARER_PREFIX.length()).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
