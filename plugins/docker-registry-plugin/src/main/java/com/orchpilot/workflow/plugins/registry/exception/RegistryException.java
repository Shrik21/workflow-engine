package com.orchpilot.workflow.plugins.registry.exception;

import com.orchpilot.workflow.sdk.context.HttpResponseView;

/**
 * A registry failure, normalised to one vocabulary across all five providers.
 *
 * <h2>Why normalise</h2>
 *
 * Docker Hub, ECR, ACR and Artifact Registry each phrase the same failure differently, and a workflow that has
 * to branch on provider-specific wording is coupled to the provider — which defeats having one capability model
 * at all. Mapping status onto a fixed set of codes means a Decision node reads {@code errorCode == 'IMAGE_NOT_FOUND'}
 * and works no matter which registry answered.
 *
 * <p>{@link #retryable()} is set deliberately, not by status class: a timeout or a 5xx may succeed on a second
 * attempt, a rate limit will after backing off, but a bad credential or a denied permission will fail
 * identically for ever and retrying it only delays the report and burns quota.
 */
public class RegistryException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public RegistryException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public static RegistryException notSupported(String provider, String operation) {
        return new RegistryException("OPERATION_NOT_SUPPORTED",
                provider + " does not support the " + operation + " operation.", false);
    }

    public static RegistryException authentication(String detail) {
        return new RegistryException("AUTHENTICATION_FAILED",
                "The registry rejected the credentials" + suffix(detail), false);
    }

    public static RegistryException network(String detail) {
        return new RegistryException("NETWORK_ERROR",
                "The registry could not be reached" + suffix(detail), true);
    }

    /**
     * Maps an HTTP response onto the normalised vocabulary.
     *
     * @param response the failed response
     * @param what     what was being addressed, e.g. {@code "repository 'example/myapp'"}, used in the message
     */
    public static RegistryException of(HttpResponseView response, String what) {
        int status = response.statusCode();
        String detail = summarise(response.body());
        return switch (status) {
            case 401 -> new RegistryException("AUTHENTICATION_FAILED",
                    "The registry rejected the credentials for " + what + suffix(detail), false);
            case 403 -> new RegistryException("AUTHORIZATION_FAILED",
                    "The credentials are not permitted to do this on " + what + suffix(detail), false);
            case 404 -> new RegistryException("NOT_FOUND", "Not found: " + what + suffix(detail), false);
            case 405, 501 -> new RegistryException("OPERATION_NOT_SUPPORTED",
                    "The registry does not support this operation on " + what + suffix(detail), false);
            case 400, 422 -> new RegistryException("INVALID_REQUEST",
                    "The registry rejected the request for " + what + " as invalid" + suffix(detail), false);
            case 409 -> new RegistryException("CONFLICT",
                    "The request conflicts with the current state of " + what + suffix(detail), false);
            case 429 -> new RegistryException("RATE_LIMITED",
                    "The registry rate limit was exceeded" + suffix(detail), true);
            default -> {
                if (status >= 500) {
                    yield new RegistryException("REGISTRY_UNAVAILABLE",
                            "The registry is temporarily unavailable (HTTP " + status + ")" + suffix(detail),
                            true);
                }
                yield new RegistryException("REGISTRY_ERROR",
                        "The registry returned HTTP " + status + " for " + what + suffix(detail), false);
            }
        };
    }

    private static String suffix(String detail) {
        return detail == null || detail.isBlank() ? "." : ": " + detail;
    }

    /**
     * Trims a response body for use in a message. Registry error bodies are small JSON documents; this keeps a
     * readable fragment without risking a multi-kilobyte error message in an execution log.
     */
    private static String summarise(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String single = body.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) + "..." : single;
    }
}
