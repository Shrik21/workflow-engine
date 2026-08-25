package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.json.Json;

import java.util.Map;

/**
 * A Compute Engine API failure, mapped to a stable OrchPilot error code the workflow can branch on.
 *
 * <h2>Meaningful errors, never leaked credentials</h2>
 *
 * Google's own error message is surfaced (it usually names the missing IAM permission, e.g.
 * {@code compute.instances.create}, or the invalid zone/machine type), but nothing this plugin holds — no token,
 * no service-account key — ever reaches the message, the code, or the logs. HTTP status maps to a code the way the
 * specification asks: 403 is a permission problem, 404 a missing instance, 429 a quota/rate limit, 5xx a transient
 * outage, and only the transient ones are retryable.
 */
public class GcpApiException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public GcpApiException(String errorCode, String message, boolean retryable) {
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

    /** Maps a non-2xx Compute response into a typed exception, extracting Google's own message safely. */
    public static GcpApiException of(HttpResponseView response) {
        int status = response.statusCode();
        String googleMessage = extractMessage(response.body());
        String detail = googleMessage == null ? "" : ": " + googleMessage;
        return switch (status) {
            case 400 -> new GcpApiException("GCP_INVALID_REQUEST",
                    "The Compute Engine request was rejected as invalid" + detail, false);
            case 401 -> new GcpApiException("GCP_AUTHENTICATION_FAILED",
                    "GCP rejected the service-account credentials" + detail, false);
            case 403 -> new GcpApiException("GCP_PERMISSION_DENIED",
                    "The configured GCP identity lacks permission for this operation" + detail, false);
            case 404 -> new GcpApiException("GCP_INSTANCE_NOT_FOUND",
                    "The Compute Engine resource was not found" + detail, false);
            case 409 -> new GcpApiException("GCP_CONFLICT",
                    "The Compute Engine request conflicts with the current state" + detail, false);
            case 429 -> new GcpApiException("GCP_QUOTA_EXCEEDED",
                    "A GCP quota or rate limit was exceeded" + detail, true);
            default -> {
                if (status >= 500) {
                    yield new GcpApiException("GCP_API_UNAVAILABLE",
                            "The Compute Engine API is temporarily unavailable (HTTP " + status + ")" + detail,
                            true);
                }
                yield new GcpApiException("GCP_API_ERROR",
                        "The Compute Engine API returned HTTP " + status + detail, false);
            }
        };
    }

    /** Pulls {@code error.message} out of a Google error body, tolerating a non-JSON or unexpected shape. */
    @SuppressWarnings("unchecked")
    static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> root && root.get("error") instanceof Map<?, ?> error) {
                Object message = ((Map<String, Object>) error).get("message");
                if (message != null) {
                    return String.valueOf(message);
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON, or not the shape we expected — fall through to a trimmed raw snippet.
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
