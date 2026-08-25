package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * Produces the 401 and 403 responses, in the same shape as every other error the API returns.
 *
 * <p>Both are deliberately uninformative. A 401 says authentication is required without distinguishing a
 * missing token from an expired or forged one, and a 403 names the permission required without describing
 * the rule that produced it. Telling a caller which of several checks failed is free reconnaissance;
 * the specific reason goes to the audit trail instead, where it is useful and not attacker-readable.
 *
 * <p>One class implements both interfaces so the two responses cannot drift apart in shape, which they
 * reliably do when written separately.
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityErrorResponder.class);

    private final SecurityAuditService audit;

    public SecurityErrorResponder(SecurityAuditService audit) {
        this.audit = audit;
    }

    /** No credentials, or credentials that did not validate. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication is required to access this resource", request.getRequestURI());
    }

    /** Authenticated, but not permitted. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String username = currentUsername();
        log.info("Access denied for {} on {} {}", username, request.getMethod(), request.getRequestURI());
        audit.failure(SecurityAuditEvent.ACCESS_DENIED, null, username, "insufficient_permissions", request);

        write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", request.getRequestURI());
    }

    private static String currentUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getPrincipal() instanceof AuthPrincipal principal
                ? principal.getUsername()
                : authentication.getName();
    }

    /**
     * Writes the error body by hand.
     *
     * <p>These responses are produced by the filter chain, before Spring MVC and its message converters are
     * involved, so there is no {@code ResponseEntity} to return. The shape matches the API's
     * {@code ApiError} so a client has one error format to parse.
     */
    private static void write(HttpServletResponse response, HttpStatus status, String error,
                              String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{"
                + "\"timestamp\":\"" + Instant.now() + "\","
                + "\"status\":" + status.value() + ","
                + "\"error\":\"" + error + "\","
                + "\"code\":\"" + error + "\","
                + "\"message\":\"" + escape(message) + "\","
                + "\"details\":[],"
                + "\"path\":\"" + escape(path) + "\""
                + "}");
    }

    /** Minimal JSON string escaping. The inputs are internal, but a request path is caller-controlled. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "").replace("\r", "");
    }
}
