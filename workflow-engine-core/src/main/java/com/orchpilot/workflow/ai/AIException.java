package com.orchpilot.workflow.ai;

/**
 * A failure talking to an AI provider — unreachable, unauthorized, rate-limited, timed out, or a bad response.
 *
 * <p>Carries a stable {@code code} so the router can decide whether a fallback provider is worth trying and the
 * node can report something actionable, and a {@code retryable} flag so a transient failure (a timeout, a rate
 * limit) is retried while a permanent one (bad credentials) is not — never blindly repeating a call that will
 * fail the same way, or worse, a destructive one.
 */
public class AIException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public AIException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public AIException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static AIException unavailable(String message, Throwable cause) {
        return new AIException("PROVIDER_UNAVAILABLE", message, true, cause);
    }

    public static AIException unauthorized(String message) {
        return new AIException("PROVIDER_UNAUTHORIZED", message, false);
    }

    public static AIException badResponse(String message) {
        return new AIException("PROVIDER_BAD_RESPONSE", message, false);
    }

    public static AIException timeout(String message) {
        return new AIException("PROVIDER_TIMEOUT", message, true);
    }
}
