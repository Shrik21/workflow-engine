package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;

/**
 * Answers an unauthenticated request with the service's own error shape.
 *
 * <p>Without this the resource server returns an empty 401 with only a {@code WWW-Authenticate} header, and the
 * workflow service, which parses {@link ApiError} to decide what to show a user, has nothing to read and reports
 * a generic failure. That exact gap has already produced one misdiagnosis in this platform.
 *
 * <p>The message says authentication is required and nothing about why the token was unacceptable. Whether a
 * token expired, was signed with the wrong key or names an unknown issuer is not information an anonymous caller
 * needs, and the log has it.
 */
class PluginServerAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(ErrorBodyWriter.write(new ApiError("UNAUTHORIZED",
                "Authentication is required to access the plugin registry.",
                java.util.List.of(), request.getRequestURI(), Instant.now())));
    }
}
