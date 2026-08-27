package com.orchpilot.workflow.dto;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape every endpoint returns.
 *
 * <p>{@code code} is stable and machine-readable; {@code message} is for humans and may be reworded without
 * notice. {@code details} carries the full list when several things are wrong at once, which is the normal
 * case for workflow and plugin validation.
 *
 * @param code    stable error identifier, e.g. {@code WORKFLOW_INVALID}
 * @param message human-readable summary
 * @param details every individual problem, empty when there is only one
 * @param path    request path that produced the error
 * @param at      when it happened
 */
public record ApiError(String code, String message, List<String> details, String path, Instant at) {

    /**
     * @param code    stable error identifier
     * @param message human-readable summary
     * @param path    request path
     * @return an error with no detail list
     */
    public static ApiError of(String code, String message, String path) {
        return new ApiError(code, message, List.of(), path, Instant.now());
    }

    /**
     * @param code    stable error identifier
     * @param message human-readable summary
     * @param details individual problems
     * @param path    request path
     * @return an error carrying every problem found
     */
    public static ApiError of(String code, String message, List<String> details, String path) {
        return new ApiError(code, message, details == null ? List.of() : List.copyOf(details), path,
                Instant.now());
    }
}
