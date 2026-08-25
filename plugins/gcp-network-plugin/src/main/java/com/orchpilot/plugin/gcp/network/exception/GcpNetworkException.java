package com.orchpilot.plugin.gcp.network.exception;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.json.Json;

import java.util.List;
import java.util.Map;

/**
 * A failure with a stable code the workflow can branch on.
 *
 * <h2>Google's message is surfaced; nothing of ours is</h2>
 *
 * The API's own text is the useful part — it names the missing IAM permission
 * ({@code compute.networks.create}), the quota that was hit, or the field that was rejected. That is carried
 * through. What never reaches a message, a code or a log is anything this plugin holds: no access token, no
 * service-account key, no private key material.
 */
public class GcpNetworkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Long enough for Google's explanation, short enough not to fill a workflow variable. */
    private static final int MAX_DETAIL = 400;

    private final String errorCode;
    private final boolean retryable;

    public GcpNetworkException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public GcpNetworkException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    /** @return whether retrying could plausibly succeed; only a rate limit or a server fault is */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Maps a non-2xx Compute response onto a code.
     *
     * @param what a short description of the attempt, for the message
     */
    public static GcpNetworkException of(HttpResponseView response, String what) {
        int status = response.statusCode();
        String detail = detail(response.body());
        String suffix = detail == null ? "" : " " + detail;

        return switch (status) {
            case 400 -> new GcpNetworkException("GCP_INVALID_ARGUMENT",
                    "GCP rejected " + what + " as invalid." + suffix, false);
            case 401 -> new GcpNetworkException("GCP_AUTHENTICATION_FAILED",
                    "Authentication failed for " + what + ". The service-account credential was rejected or "
                            + "its key has been disabled." + suffix, false);
            case 403 -> new GcpNetworkException("GCP_PERMISSION_DENIED",
                    "Permission denied for " + what + ". The service account needs the matching Compute IAM "
                            + "role on the project." + suffix, false);
            case 404 -> new GcpNetworkException("GCP_RESOURCE_NOT_FOUND",
                    "Not found: " + what + "." + suffix, false);
            case 409 -> new GcpNetworkException("GCP_RESOURCE_ALREADY_EXISTS",
                    "Already exists, or conflicts with something that does: " + what + "." + suffix, false);
            case 412 -> new GcpNetworkException("GCP_OPERATION_FAILED",
                    "A precondition failed for " + what + "; the resource changed underneath this request."
                            + suffix, false);
            case 429 -> new GcpNetworkException("GCP_QUOTA_EXCEEDED",
                    "Quota or rate limit exceeded while performing " + what + "." + suffix, true);
            default -> {
                // A 5xx is Google's problem and worth retrying; anything else is ours and is not.
                boolean serverSide = status >= 500;
                yield new GcpNetworkException(serverSide ? "GCP_API_UNAVAILABLE" : "GCP_OPERATION_FAILED",
                        "The request for " + what + " returned HTTP " + status + "." + suffix, serverSide);
            }
        };
    }

    public static GcpNetworkException invalidCidr(String field, String value, String reason) {
        return new GcpNetworkException("GCP_INVALID_CIDR",
                "'" + field + "' is not a usable range" + (value == null ? "" : " (" + value + ")")
                        + ": " + reason, false);
    }

    public static GcpNetworkException invalidArgument(String detail) {
        return new GcpNetworkException("GCP_INVALID_ARGUMENT", detail, false);
    }

    public static GcpNetworkException notFound(String what) {
        return new GcpNetworkException("GCP_RESOURCE_NOT_FOUND", what + " was not found.", false);
    }

    public static GcpNetworkException projectNotFound(String project) {
        return new GcpNetworkException("GCP_PROJECT_NOT_FOUND",
                "Project '" + project + "' was not found, or the service account cannot see it.", false);
    }

    /**
     * The resource still has dependents.
     *
     * <p>Names them, because "cannot delete, it is in use" without saying by what leaves an operator to hunt.
     */
    public static GcpNetworkException hasDependencies(String what, List<String> dependents) {
        return new GcpNetworkException("GCP_NETWORK_HAS_DEPENDENCIES",
                what + " still has dependent resources and was not deleted: " + String.join(", ", dependents)
                        + ". Remove them first — this plugin never deletes dependents for you.", false);
    }

    public static GcpNetworkException timeout(String what, long millis) {
        return new GcpNetworkException("GCP_OPERATION_TIMEOUT",
                "GCP did not finish " + what + " within " + millis + " ms. The operation may still complete; "
                        + "check the resource before retrying.", true);
    }

    /** Confirmation was required for a destructive or exposing change and was not given. */
    public static GcpNetworkException confirmationRequired(String message) {
        return new GcpNetworkException("GCP_CONFIRMATION_REQUIRED", message, false);
    }

    /**
     * Pulls the explanation out of Google's error envelope.
     *
     * <p>Compute nests it as {@code error.message}, and often carries a more specific {@code error.errors[0]}.
     * An unparsable body contributes nothing rather than being echoed raw into a workflow log.
     */
    @SuppressWarnings("unchecked")
    private static String detail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = Json.parseObject(body);
            Object error = parsed.get("error");
            if (!(error instanceof Map<?, ?> map)) {
                return null;
            }
            Map<String, Object> typed = (Map<String, Object>) map;
            Object message = typed.get("message");

            // The nested errors[] entry usually names the exact field or resource.
            if (typed.get("errors") instanceof List<?> errors && !errors.isEmpty()
                    && errors.get(0) instanceof Map<?, ?> first) {
                Object specific = ((Map<String, Object>) first).get("message");
                if (specific != null) {
                    message = specific;
                }
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
