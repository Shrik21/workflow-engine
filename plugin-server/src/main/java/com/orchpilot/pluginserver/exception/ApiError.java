package com.orchpilot.pluginserver.exception;

import java.time.Instant;
import java.util.List;

/**
 * The one error shape this service returns.
 *
 * <p>Identical in shape to the workflow platform's error body, which is not incidental: the workflow service
 * surfaces registry failures to a user through its own UI, and a second error format would mean a second parser
 * and a second set of assumptions about where the message lives.
 *
 * @param code    stable machine-readable code, upper-snake-case, safe to branch on
 * @param message one sentence written to be shown to whoever made the request
 * @param details supporting lines, such as every problem found in a rejected manifest
 * @param path    request path
 * @param at      when it happened
 */
public record ApiError(String code, String message, List<String> details, String path, Instant at) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
        at = at == null ? Instant.now() : at;
    }

    public static ApiError of(String code, String message, String path) {
        return new ApiError(code, message, List.of(), path, Instant.now());
    }

    public static ApiError of(String code, String message, List<String> details, String path) {
        return new ApiError(code, message, details, path, Instant.now());
    }
}
