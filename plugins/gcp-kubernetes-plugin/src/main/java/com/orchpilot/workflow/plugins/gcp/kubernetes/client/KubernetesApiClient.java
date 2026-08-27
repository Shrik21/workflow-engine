package com.orchpilot.workflow.plugins.gcp.kubernetes.client;

import com.orchpilot.workflow.plugins.gcp.kubernetes.model.K8sResource;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A generic client for a Kubernetes API server — five verbs over the {@link K8sResource} table.
 *
 * <h2>Vendor-neutral by construction</h2>
 *
 * This class knows a base URL and how to get a bearer token, and nothing else. It contains no Google code, which is
 * what makes it reusable verbatim by a future EKS or AKS provider: those providers differ in how they mint the
 * token and discover the endpoint, not in how they talk to Kubernetes.
 *
 * <h2>Why the token is a supplier</h2>
 *
 * Cloud access tokens expire, and a rollout wait can outlive one. Taking a {@link Supplier} means every request
 * asks for a currently-valid token rather than capturing one at construction — the token source does the caching,
 * so this costs a map lookup in the common case.
 *
 * <h2>What it deliberately cannot do</h2>
 *
 * There is no exec, attach, or port-forward. Those need a streaming protocol upgrade (SPDY or WebSocket) that the
 * engine's plugin HTTP transport does not carry — the same restriction that keeps an untrusted plugin from opening
 * arbitrary sockets from inside the engine. That is a boundary, not an omission; see the plugin's README.
 */
public final class KubernetesApiClient {

    /** Kubernetes caps a log read anyway; this keeps a runaway pod from filling a workflow variable. */
    public static final int MAX_LOG_TAIL_LINES = 2000;

    private final PluginHttpClient http;
    private final String apiServerUrl;
    private final Supplier<String> token;
    private final long timeoutMillis;

    /**
     * @param apiServerUrl the cluster API server base URL, with scheme and no trailing slash
     * @param token        supplies a currently-valid bearer token
     */
    public KubernetesApiClient(PluginHttpClient http, String apiServerUrl, Supplier<String> token,
                               long timeoutMillis) {
        this.http = http;
        this.apiServerUrl = apiServerUrl.endsWith("/")
                ? apiServerUrl.substring(0, apiServerUrl.length() - 1)
                : apiServerUrl;
        this.token = token;
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
    }

    public String apiServerUrl() {
        return apiServerUrl;
    }

    // ------------------------------------------------------------------ the five verbs

    /**
     * Lists a resource collection.
     *
     * @param labelSelector optional {@code key=value,key2=value2} selector
     * @param fieldSelector optional field selector, e.g. {@code status.phase=Running}
     * @return the raw {@code List} object, whose {@code items} holds the resources
     */
    public Map<String, Object> list(K8sResource resource, String namespace, String labelSelector,
                                    String fieldSelector, Integer limit) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "labelSelector", labelSelector);
        appendParam(query, "fieldSelector", fieldSelector);
        if (limit != null && limit > 0) {
            appendParam(query, "limit", String.valueOf(limit));
        }
        return request("GET", resource.path(namespace, null) + query, null, null,
                "listing " + resource.plural());
    }

    public Map<String, Object> get(K8sResource resource, String namespace, String name) {
        return request("GET", resource.path(namespace, name), null, null,
                resource.kind() + " '" + name + "'");
    }

    public Map<String, Object> create(K8sResource resource, String namespace, Map<String, Object> body,
                                      boolean dryRun) {
        String path = resource.path(namespace, null) + (dryRun ? "?dryRun=All" : "");
        return request("POST", path, Json.write(body), "application/json",
                "creating " + resource.kind() + " '" + nameOf(body) + "'");
    }

    public Map<String, Object> replace(K8sResource resource, String namespace, String name,
                                       Map<String, Object> body, boolean dryRun) {
        String path = resource.path(namespace, name) + (dryRun ? "?dryRun=All" : "");
        return request("PUT", path, Json.write(body), "application/json",
                "updating " + resource.kind() + " '" + name + "'");
    }

    /**
     * Applies a strategic-merge patch — the mechanism behind scale, image update and rolling restart.
     *
     * <p>A strategic merge is used rather than a JSON merge patch because it is what {@code kubectl} uses and it
     * merges list entries by key, so patching one container's image does not delete the pod's other containers.
     */
    public Map<String, Object> patch(K8sResource resource, String namespace, String name, Map<String, Object> patch,
                                     String what) {
        return request("PATCH", resource.path(namespace, name), Json.write(patch),
                "application/strategic-merge-patch+json", what);
    }

    /**
     * Deletes a resource.
     *
     * @param propagation {@code Foreground}, {@code Background} or {@code Orphan}; null lets the server decide
     */
    public Map<String, Object> delete(K8sResource resource, String namespace, String name, String propagation) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "propagationPolicy", propagation);
        return request("DELETE", resource.path(namespace, name) + query, null, null,
                "deleting " + resource.kind() + " '" + name + "'");
    }

    // ------------------------------------------------------------------ pod logs

    /**
     * Reads a pod's logs.
     *
     * <p>Logs are plain text, not JSON, so this is the one call that does not go through {@link #request}. Tail
     * lines are clamped to {@link #MAX_LOG_TAIL_LINES}: the response has to fit in a workflow variable, and an
     * unbounded read of a chatty pod would blow past the transport's response ceiling and fail opaquely.
     *
     * @param container null for the pod's only (or default) container
     * @param previous  read the previous terminated container — what you want after a CrashLoopBackOff
     */
    public String logs(String namespace, String pod, String container, int tailLines, boolean previous,
                       boolean timestamps) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "container", container);
        appendParam(query, "tailLines", String.valueOf(Math.min(Math.max(tailLines, 1), MAX_LOG_TAIL_LINES)));
        if (previous) {
            appendParam(query, "previous", "true");
        }
        if (timestamps) {
            appendParam(query, "timestamps", "true");
        }
        String url = apiServerUrl + K8sResource.POD.path(namespace, pod) + "/log" + query;
        HttpResponseView response = http.execute(HttpRequestSpec.builder("GET", url)
                .header("Authorization", "Bearer " + token.get())
                .header("Accept", "text/plain")
                .timeoutMillis(timeoutMillis)
                .build());
        if (!response.isSuccess()) {
            throw KubernetesException.of(response, "logs for pod '" + pod + "'");
        }
        return response.body() == null ? "" : response.body();
    }

    // ------------------------------------------------------------------ convenience reads

    /** @return the {@code items} of a list response, never null */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(Map<String, Object> listResponse) {
        Object items = listResponse == null ? null : listResponse.get("items");
        if (items instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        return List.of();
    }

    // ------------------------------------------------------------------ wire

    private Map<String, Object> request(String method, String path, String body, String contentType, String what) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, apiServerUrl + path)
                .header("Authorization", "Bearer " + token.get())
                .header("Accept", "application/json")
                .timeoutMillis(timeoutMillis);
        if (body != null) {
            builder.body(body).header("Content-Type",
                    contentType == null ? "application/json" : contentType);
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw KubernetesException.of(response, what);
        }
        String payload = response.body();
        if (payload == null || payload.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.parseObject(payload);
        } catch (RuntimeException ex) {
            throw new KubernetesException("K8S_INVALID_RESPONSE",
                    "The Kubernetes API returned a body that is not JSON while " + what + ".", false);
        }
    }

    private static void appendParam(StringBuilder query, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        query.append(query.isEmpty() ? '?' : '&')
                .append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static String nameOf(Map<String, Object> body) {
        if (body.get("metadata") instanceof Map<?, ?> metadata) {
            Object name = ((Map<String, Object>) metadata).get("name");
            if (name != null) {
                return String.valueOf(name);
            }
        }
        return "?";
    }
}
