package com.orchpilot.workflow.plugins.jira;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.json.Json;

import java.util.List;
import java.util.Map;

/**
 * A Jira failure, mapped to a stable error code a workflow can branch on.
 *
 * <p>Jira reports problems in two different shapes — a top-level {@code errorMessages} array and a per-field
 * {@code errors} object — and the per-field one is where the genuinely useful text lives ("customfield_10010 is
 * required"). Both are extracted, because "HTTP 400" tells an operator nothing and the field-level message
 * usually tells them exactly which configuration is wrong.
 */
public class JiraException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public JiraException(String errorCode, String message, boolean retryable) {
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

    public static JiraException of(HttpResponseView response, String what) {
        int status = response.statusCode();
        String detail = extractMessage(response.body());
        String suffix = detail == null ? "." : ": " + detail;
        return switch (status) {
            case 400 -> new JiraException("JIRA_INVALID_REQUEST",
                    "Jira rejected the request for " + what + suffix, false);
            case 401 -> new JiraException("JIRA_AUTHENTICATION_FAILED",
                    "Jira rejected the credentials" + suffix, false);
            case 403 -> new JiraException("JIRA_PERMISSION_DENIED",
                    "The Jira account is not permitted to do this on " + what + suffix, false);
            case 404 -> new JiraException("JIRA_NOT_FOUND",
                    "Not found in Jira: " + what + " (or the account cannot see it)" + suffix, false);
            case 409 -> new JiraException("JIRA_CONFLICT",
                    "The request conflicts with the current state of " + what + suffix, false);
            case 429 -> new JiraException("JIRA_RATE_LIMITED",
                    "Jira rate limit exceeded" + suffix, true);
            default -> {
                if (status >= 500) {
                    yield new JiraException("JIRA_UNAVAILABLE",
                            "Jira is temporarily unavailable (HTTP " + status + ")" + suffix, true);
                }
                yield new JiraException("JIRA_ERROR",
                        "Jira returned HTTP " + status + " for " + what + suffix, false);
            }
        };
    }

    /** Pulls both {@code errorMessages} and the per-field {@code errors} out of a Jira error body. */
    @SuppressWarnings("unchecked")
    static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> root) {
                StringBuilder text = new StringBuilder();
                Object messages = ((Map<String, Object>) root).get("errorMessages");
                if (messages instanceof List<?> list && !list.isEmpty()) {
                    text.append(String.join("; ", list.stream().map(String::valueOf).toList()));
                }
                Object errors = ((Map<String, Object>) root).get("errors");
                if (errors instanceof Map<?, ?> fields && !fields.isEmpty()) {
                    if (text.length() > 0) {
                        text.append(" | ");
                    }
                    fields.forEach((field, message) -> text.append(field).append(": ").append(message).append(' '));
                }
                if (text.length() > 0) {
                    return trim(text.toString());
                }
            }
        } catch (RuntimeException ignored) {
            // Not the JSON we expected — fall through to a raw snippet.
        }
        return trim(body);
    }

    private static String trim(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) + "..." : single;
    }
}
