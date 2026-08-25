package com.orchpilot.workflow.plugins.gcp.kubernetes.provider.gke;

import com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesException;
import com.orchpilot.workflow.plugins.gcp.kubernetes.provider.ClusterAdmin;
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
 * Cluster and node-pool lifecycle against the GKE management API at {@code container.googleapis.com}.
 *
 * <h2>This half always works</h2>
 *
 * Unlike the cluster's own API server, {@code container.googleapis.com} presents a publicly-trusted certificate and
 * is reachable through the engine's HTTP client with no special handling. Cluster inventory, health and node-pool
 * scaling therefore work for every GKE cluster, including private ones whose API server this plugin cannot reach.
 *
 * <h2>Long-running operations are reported, not awaited</h2>
 *
 * Creating or deleting a cluster takes minutes. Rather than hold a node's thread open for that, these methods
 * return GKE's operation record so the node can surface {@code operationId} and the workflow can poll with a
 * Get Cluster node or simply carry on. Blocking a worker for a ten-minute cluster build is exactly the kind of
 * thing that makes an engine's thread pool the bottleneck.
 */
public final class GkeClusterAdmin implements ClusterAdmin {

    static final String BASE_URL = "https://container.googleapis.com/v1";

    private final PluginHttpClient http;
    private final Supplier<String> token;
    private final long timeoutMillis;

    GkeClusterAdmin(PluginHttpClient http, Supplier<String> token, long timeoutMillis) {
        this.http = http;
        this.token = token;
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listClusters(ClusterRef ref) {
        Map<String, Object> response = request("GET", parent(ref) + "/clusters", null,
                "listing GKE clusters");
        List<Map<String, Object>> clusters = new ArrayList<>();
        if (response.get("clusters") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> cluster) {
                    clusters.add((Map<String, Object>) cluster);
                }
            }
        }
        return clusters;
    }

    @Override
    public Map<String, Object> getCluster(ClusterRef ref) {
        return request("GET", clusterPath(ref), null, "GKE cluster '" + ref.cluster() + "'");
    }

    @Override
    public Map<String, Object> createCluster(ClusterRef ref, Map<String, Object> spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cluster", spec);
        return request("POST", parent(ref) + "/clusters", Json.write(body),
                "creating GKE cluster '" + ref.cluster() + "'");
    }

    @Override
    public Map<String, Object> deleteCluster(ClusterRef ref) {
        return request("DELETE", clusterPath(ref), null, "deleting GKE cluster '" + ref.cluster() + "'");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listNodePools(ClusterRef ref) {
        Map<String, Object> response = request("GET", clusterPath(ref) + "/nodePools", null,
                "listing node pools of GKE cluster '" + ref.cluster() + "'");
        List<Map<String, Object>> pools = new ArrayList<>();
        if (response.get("nodePools") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> pool) {
                    pools.add((Map<String, Object>) pool);
                }
            }
        }
        return pools;
    }

    @Override
    public Map<String, Object> scaleNodePool(ClusterRef ref, String nodePool, int nodeCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeCount", nodeCount);
        // GKE spells this as a custom method on the resource, hence the ":setSize" suffix rather than a PATCH.
        return request("POST", clusterPath(ref) + "/nodePools/" + encode(nodePool) + ":setSize", Json.write(body),
                "scaling node pool '" + nodePool + "'");
    }

    @Override
    public Map<String, Object> deleteNodePool(ClusterRef ref, String nodePool) {
        return request("DELETE", clusterPath(ref) + "/nodePools/" + encode(nodePool), null,
                "deleting node pool '" + nodePool + "'");
    }

    // ------------------------------------------------------------------ wire

    private String parent(ClusterRef ref) {
        return "/projects/" + encode(ref.scope()) + "/locations/" + encode(ref.locationOrWildcard());
    }

    private String clusterPath(ClusterRef ref) {
        return parent(ref) + "/clusters/" + encode(ref.cluster());
    }

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
                    "The GKE API returned a body that is not JSON while " + what + ".", false);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
