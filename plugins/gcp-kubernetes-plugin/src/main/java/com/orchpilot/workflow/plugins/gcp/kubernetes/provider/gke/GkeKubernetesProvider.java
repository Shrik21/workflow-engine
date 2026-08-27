package com.orchpilot.workflow.plugins.gcp.kubernetes.provider.gke;

import com.orchpilot.workflow.plugins.gcp.kubernetes.client.GoogleCredentials;
import com.orchpilot.workflow.plugins.gcp.kubernetes.client.GoogleTokenSource;
import com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesApiClient;
import com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesException;
import com.orchpilot.workflow.plugins.gcp.kubernetes.provider.ClusterAdmin;
import com.orchpilot.workflow.plugins.gcp.kubernetes.provider.KubernetesProvider;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The Google Kubernetes Engine implementation of {@link KubernetesProvider}.
 *
 * <h2>Reaching the cluster API server: the constraint that shapes this class</h2>
 *
 * A GKE cluster's API server presents a certificate signed by that cluster's <em>own</em> certificate authority —
 * which is precisely why the GKE API hands back {@code masterAuth.clusterCaCertificate}. Trusting it requires
 * installing that CA into the client's trust material, and the engine's plugin HTTP client is a shared, engine-owned
 * JDK client with no per-plugin TLS hook. That is a deliberate isolation property, not an oversight: letting a
 * plugin swap in its own trust manager would let any plugin weaken TLS for the whole engine.
 *
 * <p>So this provider connects the way that <em>is</em> safe — GKE's <strong>DNS-based control plane endpoint</strong>
 * ({@code gke-….gke.goog}), which Google fronts with a publicly-trusted certificate the default JDK trust store
 * already accepts. When a cluster exposes one, workload operations work with no configuration at all. When it does
 * not, {@link #connect} fails immediately with an actionable message rather than surfacing an opaque TLS handshake
 * error twenty seconds later.
 *
 * <p>Cluster and node-pool management is unaffected — {@link GkeClusterAdmin} talks to {@code container.googleapis.com},
 * which is publicly trusted and always reachable.
 */
public final class GkeKubernetesProvider implements KubernetesProvider {

    public static final String ID = "gcp-gke";

    /** Shared across nodes so a workflow's many Kubernetes calls mint one token, not one per node. */
    private final GoogleTokenSource tokens;

    public GkeKubernetesProvider(GoogleTokenSource tokens) {
        this.tokens = tokens;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Google Kubernetes Engine";
    }

    @Override
    public ClusterAdmin admin(NodeConfiguration configuration, PluginContext context, long timeoutMillis) {
        return new GkeClusterAdmin(context.http(), tokenSupplier(configuration, context), timeoutMillis);
    }

    @Override
    public KubernetesApiClient connect(NodeConfiguration configuration, PluginContext context, long timeoutMillis) {
        Supplier<String> token = tokenSupplier(configuration, context);

        // An explicit override exists for clusters fronted by a gateway with its own publicly-trusted certificate.
        String override = configuration.getString("apiServerUrl", null);
        if (override != null && !override.isBlank()) {
            requireHttps(override);
            return new KubernetesApiClient(context.http(), override.trim(), token, timeoutMillis);
        }

        ClusterAdmin.ClusterRef ref = clusterRef(configuration);
        Map<String, Object> cluster = new GkeClusterAdmin(context.http(), token, timeoutMillis).getCluster(ref);
        return new KubernetesApiClient(context.http(), endpointOf(cluster, ref), token, timeoutMillis);
    }

    /**
     * Builds the cluster reference from node configuration, falling back to the credential's own project.
     *
     * <p>Falling back matters in practice: the project is already in the service-account key, so making every node
     * repeat it is friction with no benefit.
     */
    public ClusterAdmin.ClusterRef clusterRef(NodeConfiguration configuration) {
        return new ClusterAdmin.ClusterRef(
                configuration.getString("projectId", null),
                configuration.getString("location", null),
                configuration.getString("clusterName", null));
    }

    /** Resolves the credential once and hands back a supplier that always returns a currently-valid token. */
    private Supplier<String> tokenSupplier(NodeConfiguration configuration, PluginContext context) {
        String secretName = configuration.requireString("credentialsSecret");
        GoogleCredentials credentials =
                GoogleCredentials.fromServiceAccountJson(context.secrets().require(secretName));
        return () -> tokens.accessToken(credentials, context.http());
    }

    /** @return the project the node configured, or the one baked into the credential */
    public String resolveProject(NodeConfiguration configuration, PluginContext context) {
        String configured = configuration.getString("projectId", null);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        GoogleCredentials credentials = GoogleCredentials.fromServiceAccountJson(
                context.secrets().require(configuration.requireString("credentialsSecret")));
        String fromKey = credentials.projectId();
        if (fromKey == null || fromKey.isBlank()) {
            throw new PluginConfigurationException(
                    "No GCP project: set 'projectId' on the node, or use a service-account key that names one.");
        }
        return fromKey;
    }

    // ------------------------------------------------------------------ endpoint selection

    /**
     * Picks an endpoint whose certificate the engine can actually verify.
     *
     * <p>Only the DNS-based control plane endpoint qualifies. The legacy {@code endpoint} field is an IP address
     * served by the per-cluster CA, so it is rejected with an explanation instead of being attempted.
     */
    @SuppressWarnings("unchecked")
    static String endpointOf(Map<String, Object> cluster, ClusterAdmin.ClusterRef ref) {
        String dns = null;
        if (cluster.get("controlPlaneEndpointsConfig") instanceof Map<?, ?> endpoints
                && ((Map<String, Object>) endpoints).get("dnsEndpointConfig") instanceof Map<?, ?> dnsConfig) {
            Object value = ((Map<String, Object>) dnsConfig).get("endpoint");
            if (value != null && !String.valueOf(value).isBlank()) {
                dns = String.valueOf(value).trim();
            }
        }
        if (dns != null) {
            return dns.startsWith("http") ? dns : "https://" + dns;
        }
        throw new KubernetesException("K8S_ENDPOINT_NOT_TRUSTED",
                "Cluster '" + ref.cluster() + "' has no DNS-based control plane endpoint, so its API server "
                        + "cannot be reached over a verifiable TLS connection. Its IP endpoint is served by the "
                        + "cluster's own certificate authority, which the engine's shared HTTP client does not "
                        + "trust. Enable the DNS endpoint on the cluster (GKE: control plane access → "
                        + "'DNS-based endpoint'), or set 'apiServerUrl' to a gateway with a publicly-trusted "
                        + "certificate. GKE cluster and node-pool operations are unaffected and continue to work.",
                false);
    }

    private static void requireHttps(String url) {
        if (!url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new PluginConfigurationException(
                    "'apiServerUrl' must be an https:// URL — a bearer token must never be sent in clear text.");
        }
    }
}
