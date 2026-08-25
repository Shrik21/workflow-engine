package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

/**
 * A thin GitHub REST client over the engine's {@link PluginHttpClient}.
 *
 * <p>It attaches the token and the standard GitHub headers, issues one call, and returns the raw response body on
 * success (or throws a {@link GithubApiException} mapped from the status). It knows nothing about node
 * configuration, operations or workflow variables — just the wire — which keeps it trivial to test against canned
 * JSON with no GitHub account. The base URL is injected so GitHub Enterprise Server (…/api/v3) works unchanged.
 */
public final class GithubClient {

    static final String DEFAULT_BASE_URL = "https://api.github.com";
    private static final String API_VERSION = "2022-11-28";

    private final PluginHttpClient http;
    private final String baseUrl;
    private final long timeoutMillis;

    public GithubClient(PluginHttpClient http, String baseUrl, long timeoutMillis) {
        this.http = http;
        this.baseUrl = normalize(baseUrl);
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
    }

    /**
     * @param method HTTP method
     * @param path   path beginning with '/', appended to the base URL
     * @param token  the GitHub token (never logged)
     * @param body   JSON body, or null for GET/DELETE with no body
     * @return the raw response body on a 2xx
     * @throws GithubApiException on any non-2xx status
     */
    public String request(String method, String path, String token, String body) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, baseUrl + path)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .timeoutMillis(timeoutMillis);
        if (body != null) {
            builder.body(body).header("Content-Type", "application/json");
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw GithubApiException.of(response);
        }
        return response.body();
    }

    String baseUrl() {
        return baseUrl;
    }

    private static String normalize(String url) {
        String base = url == null || url.isBlank() ? DEFAULT_BASE_URL : url.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
