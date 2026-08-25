package com.orchpilot.workflow.plugins.gcp.kubernetes.client;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.json.Json;

import java.util.Map;

/**
 * A failure from either API this plugin talks to, normalised onto one stable set of error codes.
 *
 * <h2>Why the codes are shared across both layers</h2>
 *
 * GKE returns Google's {@code {"error": {"message": …}}} envelope and Kubernetes returns a {@code Status} object;
 * a workflow author branching on "did this fail because it does not exist" should not have to know which. Both
 * shapes are folded into the same codes here, so a Decision node reads {@code errorCode == 'K8S_NOT_FOUND'}
 * regardless of which API produced it.
 *
 * <h2>Retryable is a property of the failure, not the caller</h2>
 *
 * {@code 429} and {@code 5xx} are marked retryable so the engine's own retry policy can act on them; a {@code 404}
 * or a {@code 403} never is, because retrying cannot change the outcome. Messages carry the API's own explanation
 * where there is one — but never a token, and never the response body wholesale.
 */
public final class KubernetesException extends RuntimeException {

    private static final int MAX_DETAIL = 400;

    private final String errorCode;
    private final boolean retryable;

    public KubernetesException(String errorCode, String message, boolean retryable) {
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

    /** Maps an unsuccessful response onto a code, a human explanation, and whether retrying could help. */
    public static KubernetesException of(HttpResponseView response, String what) {
        int status = response.statusCode();
        String detail = detail(response.body());
        String suffix = detail == null ? "" : " " + detail;
        return switch (status) {
            case 400 -> new KubernetesException("K8S_INVALID_REQUEST",
                    "Kubernetes rejected the request for " + what + " as invalid." + suffix, false);
            case 401 -> new KubernetesException("K8S_AUTHENTICATION_FAILED",
                    "Authentication failed for " + what + ". The service-account credential was rejected or its "
                            + "access token has expired." + suffix, false);
            case 403 -> new KubernetesException("K8S_PERMISSION_DENIED",
                    "Permission denied for " + what + ". The service account needs the matching IAM role "
                            + "(for GKE) or RBAC binding (inside the cluster)." + suffix, false);
            case 404 -> new KubernetesException("K8S_NOT_FOUND",
                    "Not found: " + what + "." + suffix, false);
            case 409 -> new KubernetesException("K8S_CONFLICT",
                    "Conflict on " + what + ". It already exists, or was modified concurrently." + suffix, false);
            case 422 -> new KubernetesException("K8S_INVALID_REQUEST",
                    "Kubernetes could not process " + what + "; the manifest is well-formed but not valid."
                            + suffix, false);
            case 429 -> new KubernetesException("K8S_RATE_LIMITED",
                    "Rate limited while performing " + what + "." + suffix, true);
            default -> {
                boolean serverSide = status >= 500;
                yield new KubernetesException(serverSide ? "K8S_UNAVAILABLE" : "K8S_REQUEST_FAILED",
                        "The request for " + what + " returned HTTP " + status + "." + suffix, serverSide);
            }
        };
    }

    /**
     * Pulls the explanation out of whichever envelope the API used.
     *
     * <p>Kubernetes uses a {@code Status} object with a top-level {@code message}; Google uses a nested
     * {@code error.message}. Anything else is ignored rather than guessed at — an unparsable body contributes no
     * detail instead of leaking raw response text into a workflow log.
     */
    private static String detail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = Json.parseObject(body);
            Object message = parsed.get("message");
            if (message == null && parsed.get("error") instanceof Map<?, ?> error) {
                message = error.get("message");
            }
            if (message == null) {
                return null;
            }
            String text = String.valueOf(message).trim();
            if (text.isEmpty()) {
                return null;
            }
            return text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) + "…" : text;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
