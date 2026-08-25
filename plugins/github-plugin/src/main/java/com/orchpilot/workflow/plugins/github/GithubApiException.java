package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.json.Json;

import java.util.Map;

/**
 * A GitHub API failure, mapped to a stable OrchPilot error code the workflow can branch on.
 *
 * <h2>Meaningful errors, never a leaked token</h2>
 *
 * GitHub's own {@code message} is surfaced (it names the validation problem or the missing scope), but nothing this
 * plugin holds — no token — ever reaches the message, the code or the logs. HTTP status maps to a code the way the
 * platform expects: 401 is a bad credential, 404 a missing resource, 422 a validation failure, and a rate limit
 * (which GitHub signals as a 403 with {@code X-RateLimit-Remaining: 0}, or a 429) is the retryable case, as are 5xx.
 */
public class GithubApiException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public GithubApiException(String errorCode, String message, boolean retryable) {
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

    public static GithubApiException of(HttpResponseView response) {
        int status = response.statusCode();
        String message = extractMessage(response.body());
        String detail = message == null ? "" : ": " + message;
        boolean rateLimited = "0".equals(response.firstHeader("X-RateLimit-Remaining"))
                || status == 429;
        return switch (status) {
            case 401 -> new GithubApiException("GITHUB_AUTHENTICATION_FAILED",
                    "GitHub rejected the token" + detail, false);
            case 403 -> rateLimited
                    ? new GithubApiException("GITHUB_RATE_LIMITED",
                            "GitHub rate limit exceeded — retry after it resets" + detail, true)
                    : new GithubApiException("GITHUB_PERMISSION_DENIED",
                            "The token lacks permission (scope) for this operation" + detail, false);
            case 404 -> new GithubApiException("GITHUB_NOT_FOUND",
                    "The GitHub resource was not found (or the token cannot see it)" + detail, false);
            case 409 -> new GithubApiException("GITHUB_CONFLICT",
                    "The GitHub request conflicts with the current state" + detail, false);
            case 422 -> new GithubApiException("GITHUB_VALIDATION_FAILED",
                    "GitHub rejected the request as invalid" + detail, false);
            case 429 -> new GithubApiException("GITHUB_RATE_LIMITED",
                    "GitHub rate limit exceeded" + detail, true);
            default -> {
                if (status >= 500) {
                    yield new GithubApiException("GITHUB_API_UNAVAILABLE",
                            "GitHub is temporarily unavailable (HTTP " + status + ")" + detail, true);
                }
                yield new GithubApiException("GITHUB_API_ERROR",
                        "GitHub returned HTTP " + status + detail, false);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> root && root.get("message") != null) {
                String message = String.valueOf(((Map<String, Object>) root).get("message"));
                Object errors = ((Map<String, Object>) root).get("errors");
                return errors == null ? message : message + " (" + errors + ")";
            }
        } catch (RuntimeException ignored) {
            // Not the JSON we expected — fall through to a trimmed raw snippet.
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
