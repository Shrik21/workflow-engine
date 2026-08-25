package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A thin Compute Engine v1 REST client, over the engine's {@link PluginHttpClient}.
 *
 * <p>Every method issues one call to {@code compute.googleapis.com}, attaches the bearer token, and returns the
 * parsed JSON body (or throws a {@link GcpApiException} mapped from the HTTP status). It deliberately knows nothing
 * about the plugin's configuration, auth flow or workflow model — it is just the wire, which keeps it trivial to
 * test against canned responses without a Google account.
 */
public final class GcpComputeClient {

    /** The stable base URL is not configurable — a plugin talking to a fake "compute" host is a security hole. */
    static final String BASE_URL = "https://compute.googleapis.com/compute/v1";

    private final PluginHttpClient http;
    private final long timeoutMillis;

    public GcpComputeClient(PluginHttpClient http, long timeoutMillis) {
        this.http = http;
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
    }

    public Map<String, Object> insertInstance(String token, String project, String zone,
                                              Map<String, Object> instance) {
        return request("POST", instancesUrl(project, zone), token, Json.write(instance));
    }

    public Map<String, Object> getInstance(String token, String project, String zone, String name) {
        return request("GET", instancesUrl(project, zone) + "/" + encode(name), token, null);
    }

    public Map<String, Object> listInstances(String token, String project, String zone, String filter,
                                             String pageToken) {
        String url = instancesUrl(project, zone) + query(filter, pageToken);
        return request("GET", url, token, null);
    }

    public Map<String, Object> aggregatedList(String token, String project, String filter, String pageToken) {
        String url = BASE_URL + "/projects/" + encode(project) + "/aggregated/instances" + query(filter, pageToken);
        return request("GET", url, token, null);
    }

    /** start / stop / reset / suspend / resume — the verb is the trailing path segment Compute expects. */
    public Map<String, Object> instanceAction(String token, String project, String zone, String name,
                                              String verb) {
        return request("POST", instancesUrl(project, zone) + "/" + encode(name) + "/" + verb, token, "");
    }

    public Map<String, Object> deleteInstance(String token, String project, String zone, String name) {
        return request("DELETE", instancesUrl(project, zone) + "/" + encode(name), token, null);
    }

    public Map<String, Object> getZoneOperation(String token, String project, String zone, String operationName) {
        return request("GET",
                BASE_URL + "/projects/" + encode(project) + "/zones/" + encode(zone) + "/operations/"
                        + encode(operationName), token, null);
    }

    private Map<String, Object> request(String method, String url, String token, String body) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, url)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeoutMillis(timeoutMillis);
        if (body != null) {
            builder.body(body).header("Content-Type", "application/json");
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw GcpApiException.of(response);
        }
        if (response.body() == null || response.body().isBlank()) {
            return new LinkedHashMap<>();
        }
        return Json.parseObject(response.body());
    }

    private static String instancesUrl(String project, String zone) {
        return BASE_URL + "/projects/" + encode(project) + "/zones/" + encode(zone) + "/instances";
    }

    private static String query(String filter, String pageToken) {
        StringBuilder q = new StringBuilder();
        if (filter != null && !filter.isBlank()) {
            q.append(q.length() == 0 ? '?' : '&').append("filter=").append(encode(filter));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            q.append(q.length() == 0 ? '?' : '&').append("pageToken=").append(encode(pageToken));
        }
        return q.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
