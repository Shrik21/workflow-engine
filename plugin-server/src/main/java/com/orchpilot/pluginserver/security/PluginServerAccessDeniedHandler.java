package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Answers an authenticated but unauthorised request.
 *
 * <p>Logged at info with the caller's name and the path, because a service client being refused is either a
 * misconfigured deployment or somebody probing, and both are worth being able to find afterwards. The response
 * names no permission: telling a caller which authority would have worked is a hint about the shape of the
 * system they are not entitled to.
 */
class PluginServerAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(PluginServerAccessDeniedHandler.class);

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException deniedException) throws IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        log.info("Refused {} {} for '{}'", request.getMethod(), request.getRequestURI(),
                authentication == null ? "anonymous" : authentication.getName());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(ErrorBodyWriter.write(new ApiError("FORBIDDEN",
                "You do not have permission to perform this action on the plugin registry.",
                List.of(), request.getRequestURI(), Instant.now())));
    }
}
