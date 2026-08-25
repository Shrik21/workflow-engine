package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-IP rate limit on the public form endpoints, with a stricter ceiling on submit.
 *
 * <h2>What it protects and how</h2>
 *
 * The public endpoints are the one unauthenticated attack surface, so they are throttled per client IP: a fixed
 * one-minute window with a general ceiling and a lower one for {@code /submit}, both configurable. It answers a
 * bot hammering random tokens with {@code 429} long before that reaches the database, but the window resets
 * every minute, so a real customer who paused and came back is never permanently locked out on IP alone.
 *
 * <p>In-memory and per-instance, which is right for the single-node deployment; a multi-instance deployment
 * would move the counter to a shared store (Redis) behind the same filter — that is the one line that changes.
 * Non-public paths pass straight through untouched.
 */
@Component
@Order(5)
public class PublicFormRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicFormRateLimitFilter.class);
    private static final String PUBLIC_PREFIX = "/api/public/forms";

    private final WorkflowEngineProperties properties;
    private final ConcurrentHashMap<String, Window> general = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> submit = new ConcurrentHashMap<>();

    public PublicFormRateLimitFilter(WorkflowEngineProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(PUBLIC_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        WorkflowEngineProperties.ExternalForm config = properties.getExternalForm();
        boolean isSubmit = path.endsWith("/submit");

        // The general limit applies to every public call; a submit additionally passes the stricter submit
        // limit, so a flood of submits is caught by whichever ceiling it hits first.
        if (exceeded(general, ip, config.getRateLimitPerMinute())
                || (isSubmit && exceeded(submit, ip, config.getSubmitRateLimitPerMinute()))) {
            log.debug("Rate limit hit for {} on {}", ip, path);
            tooManyRequests(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Fixed one-minute window: increments the count, rolling the window when the minute changes. */
    private boolean exceeded(ConcurrentHashMap<String, Window> windows, String ip, int limit) {
        if (limit <= 0) {
            return false; // A non-positive limit disables that ceiling.
        }
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(ip, (key, existing) -> {
            if (existing == null || existing.minute != minute) {
                return new Window(minute);
            }
            return existing;
        });
        return window.count.incrementAndGet() > limit;
    }

    private static void tooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429); // Too Many Requests — not a named constant in the servlet API.
        response.setHeader("Retry-After", "60");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"errorCode\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please wait and try again.\"}");
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** One IP's counter for one minute. */
    private static final class Window {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
