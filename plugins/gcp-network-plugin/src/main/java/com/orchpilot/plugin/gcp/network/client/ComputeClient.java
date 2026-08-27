package com.orchpilot.plugin.gcp.network.client;

import com.orchpilot.plugin.gcp.network.exception.GcpNetworkException;
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
 * A thin Compute Engine v1 client covering the network resources, over the engine's HTTP client.
 *
 * <h2>Generic by path, not by resource</h2>
 *
 * Compute's REST surface is uniform: a global collection is
 * {@code /projects/{p}/global/{plural}[/{name}]}, a regional one inserts {@code /regions/{r}}, and every verb
 * is the same five. Encoding that once means networks, subnetworks, firewalls, routes and routers share one
 * implementation rather than five near-identical clients — and adding another resource is a call site, not a
 * class.
 *
 * <h2>Long-running operations</h2>
 *
 * Almost every mutation returns an {@code Operation} rather than the finished resource. {@link #await} polls it
 * to completion, because a workflow that reports success while GCP is still building the subnet will have its
 * next node fail on a resource that does not exist yet. Polling checks the execution's cancellation flag, so a
 * cancelled run stops promptly instead of holding a thread for the full timeout.
 */
public final class ComputeClient {

    /** Not configurable. A plugin pointed at a look-alike "compute" host is a credential-exfiltration hole. */
    static final String BASE_URL = "https://compute.googleapis.com/compute/v1";

    private final PluginHttpClient http;
    private final Supplier<String> token;
    private final long timeoutMillis;

    public ComputeClient(PluginHttpClient http, Supplier<String> token, long timeoutMillis) {
        this.http = http;
        this.token = token;
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
    }

    // ------------------------------------------------------------------ paths

    /** @return {@code /projects/{p}/global/{plural}} */
    public static String globalPath(String project, String plural) {
        return "/projects/" + encode(project) + "/global/" + plural;
    }

    /** @return {@code /projects/{p}/regions/{r}/{plural}} */
    public static String regionalPath(String project, String region, String plural) {
        return "/projects/" + encode(project) + "/regions/" + encode(region) + "/" + plural;
    }

    /** @return the aggregated view across every region, used by list-everywhere and by inspection */
    public static String aggregatedPath(String project, String plural) {
        return "/projects/" + encode(project) + "/aggregated/" + plural;
    }

    // ------------------------------------------------------------------ verbs

    public Map<String, Object> get(String path, String what) {
        return request("GET", path, null, what);
    }

    public Map<String, Object> insert(String path, Map<String, Object> body, String what) {
        return request("POST", path, Json.write(body), what);
    }

    /**
     * Partial update. Compute uses PATCH for the handful of mutable fields on these resources; a PUT would
     * require sending the whole resource back and would silently reset anything omitted.
     */
    public Map<String, Object> patch(String path, Map<String, Object> body, String what) {
        return request("PATCH", path, Json.write(body), what);
    }

    public Map<String, Object> delete(String path, String what) {
        return request("DELETE", path, null, what);
    }

    /** POST to a custom method such as {@code addPeering}, which takes a body but is not an insert. */
    public Map<String, Object> action(String path, Map<String, Object> body, String what) {
        return request("POST", path, Json.write(body), what);
    }

    /**
     * Reads every page of a collection.
     *
     * @param filter optional Compute filter expression
     * @param cap    stop after this many items, so a project with tens of thousands of routes cannot produce
     *               an output too large to store in a workflow variable
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(String path, String filter, int cap, String what) {
        List<Map<String, Object>> items = new ArrayList<>();
        String pageToken = null;
        do {
            StringBuilder query = new StringBuilder();
            appendParam(query, "filter", filter);
            appendParam(query, "pageToken", pageToken);
            appendParam(query, "maxResults", "500");

            Map<String, Object> page = request("GET", path + query, null, what);
            if (page.get("items") instanceof List<?> raw) {
                for (Object item : raw) {
                    if (item instanceof Map<?, ?> map && items.size() < cap) {
                        items.add((Map<String, Object>) map);
                    }
                }
            }
            pageToken = page.get("nextPageToken") instanceof String next && !next.isBlank() ? next : null;
        } while (pageToken != null && items.size() < cap);
        return items;
    }

    /**
     * Flattens an aggregated list, which arrives keyed by scope rather than as a flat array.
     *
     * @param key the per-scope array name, e.g. {@code subnetworks}
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listAggregated(String path, String key, String filter, int cap,
                                                    String what) {
        StringBuilder query = new StringBuilder();
        appendParam(query, "filter", filter);
        appendParam(query, "maxResults", "500");

        Map<String, Object> response = request("GET", path + query, null, what);
        List<Map<String, Object>> items = new ArrayList<>();
        if (response.get("items") instanceof Map<?, ?> byScope) {
            for (Object scope : ((Map<String, Object>) byScope).values()) {
                if (scope instanceof Map<?, ?> scoped && ((Map<String, Object>) scoped).get(key)
                        instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && items.size() < cap) {
                            items.add((Map<String, Object>) map);
                        }
                    }
                }
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ operations

    /**
     * Waits for a long-running operation to finish.
     *
     * <p>Returns the completed operation. A GCP operation that finishes with an {@code error} is surfaced as a
     * failure rather than reported as success — the HTTP call that started it having returned 200 says only
     * that the request was accepted.
     *
     * @param operation  the operation resource the mutation returned
     * @param project    the project it belongs to
     * @param cancelled  polled between attempts so a cancelled execution stops promptly
     * @param deadline   wall-clock budget in milliseconds
     */
    public Map<String, Object> await(Map<String, Object> operation, String project,
                                     java.util.function.BooleanSupplier cancelled, long deadline,
                                     String what) {
        String name = text(operation.get("name"));
        if (name == null) {
            // Not every call returns an operation; a read-modify-write on a router returns the router.
            return operation;
        }
        String path = operationPath(operation, project, name);
        long expiresAt = System.currentTimeMillis() + deadline;
        long interval = 1_000;

        Map<String, Object> current = operation;
        while (!"DONE".equals(text(current.get("status")))) {
            if (cancelled.getAsBoolean()) {
                throw new GcpNetworkException("GCP_OPERATION_FAILED",
                        "The execution was cancelled while waiting for " + what + ".", false);
            }
            if (System.currentTimeMillis() > expiresAt) {
                throw GcpNetworkException.timeout(what, deadline);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new GcpNetworkException("GCP_OPERATION_FAILED",
                        "Interrupted while waiting for " + what + ".", false);
            }
            // Backs off to five seconds: a VPC appears in a second, a NAT can take considerably longer, and
            // polling every second for two minutes is a lot of requests for no benefit.
            interval = Math.min(interval * 2, 5_000);
            current = request("GET", path, null, "the status of " + what);
        }
        requireNoOperationError(current, what);
        return current;
    }

    /**
     * Works out where to poll an operation.
     *
     * <p>Compute puts global, regional and zonal operations in different collections, and the operation
     * resource itself says which it is through its {@code region} or {@code zone} field.
     */
    private static String operationPath(Map<String, Object> operation, String project, String name) {
        String region = lastSegment(text(operation.get("region")));
        if (region != null) {
            return "/projects/" + encode(project) + "/regions/" + encode(region) + "/operations/"
                    + encode(name);
        }
        String zone = lastSegment(text(operation.get("zone")));
        if (zone != null) {
            return "/projects/" + encode(project) + "/zones/" + encode(zone) + "/operations/" + encode(name);
        }
        return "/projects/" + encode(project) + "/global/operations/" + encode(name);
    }

    /** A completed operation can still have failed; its errors carry the reason the request did not. */
    @SuppressWarnings("unchecked")
    private static void requireNoOperationError(Map<String, Object> operation, String what) {
        Object error = operation.get("error");
        if (!(error instanceof Map<?, ?> map)) {
            return;
        }
        Object errors = ((Map<String, Object>) map).get("errors");
        StringBuilder detail = new StringBuilder();
        if (errors instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> item) {
                    Object message = ((Map<String, Object>) item).get("message");
                    if (message != null) {
                        if (!detail.isEmpty()) {
                            detail.append("; ");
                        }
                        detail.append(message);
                    }
                }
            }
        }
        throw new GcpNetworkException("GCP_OPERATION_FAILED",
                "GCP could not complete " + what + (detail.isEmpty() ? "." : ": " + detail + "."), false);
    }

    // ------------------------------------------------------------------ wire

    private Map<String, Object> request(String method, String path, String body, String what) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, BASE_URL + path)
                .header("Authorization", "Bearer " + token.get())
                .header("Accept", "application/json")
                .timeoutMillis(timeoutMillis);
        if (body != null) {
            builder.body(body).header("Content-Type", "application/json");
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw GcpNetworkException.of(response, what);
        }
        String payload = response.body();
        if (payload == null || payload.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.parseObject(payload);
        } catch (RuntimeException ex) {
            throw new GcpNetworkException("GCP_OPERATION_FAILED",
                    "The Compute API returned a body that is not JSON while " + what + ".", false);
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

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Compute returns fully-qualified self-links; the last path segment is the usable name. */
    public static String lastSegment(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
