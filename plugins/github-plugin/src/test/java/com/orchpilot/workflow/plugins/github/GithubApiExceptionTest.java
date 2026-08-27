package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Status maps to a stable code, GitHub's message is surfaced, and the tricky case is handled: a 403 is a rate
 * limit (retryable) only when {@code X-RateLimit-Remaining} is 0, otherwise it is a permission failure.
 */
class GithubApiExceptionTest {

    private static HttpResponseView response(int status, Map<String, List<String>> headers, String body) {
        return new HttpResponseView(status, headers, body, 1);
    }

    @Test
    void forbiddenIsPermissionUnlessRateLimited() {
        GithubApiException permission = GithubApiException.of(
                response(403, Map.of("X-RateLimit-Remaining", List.of("42")), "{\"message\":\"Resource not accessible\"}"));
        assertThat(permission.errorCode()).isEqualTo("GITHUB_PERMISSION_DENIED");
        assertThat(permission.retryable()).isFalse();

        GithubApiException rateLimited = GithubApiException.of(
                response(403, Map.of("X-RateLimit-Remaining", List.of("0")), "{\"message\":\"API rate limit exceeded\"}"));
        assertThat(rateLimited.errorCode()).isEqualTo("GITHUB_RATE_LIMITED");
        assertThat(rateLimited.retryable()).isTrue();
    }

    @Test
    void otherStatusesMapAsExpected() {
        assertThat(GithubApiException.of(response(401, Map.of(), "{}")).errorCode())
                .isEqualTo("GITHUB_AUTHENTICATION_FAILED");
        assertThat(GithubApiException.of(response(404, Map.of(), "{}")).errorCode())
                .isEqualTo("GITHUB_NOT_FOUND");
        assertThat(GithubApiException.of(response(422, Map.of(), "{\"message\":\"Validation Failed\"}")).errorCode())
                .isEqualTo("GITHUB_VALIDATION_FAILED");
        GithubApiException server = GithubApiException.of(response(502, Map.of(), "{}"));
        assertThat(server.errorCode()).isEqualTo("GITHUB_API_UNAVAILABLE");
        assertThat(server.retryable()).isTrue();
    }
}
