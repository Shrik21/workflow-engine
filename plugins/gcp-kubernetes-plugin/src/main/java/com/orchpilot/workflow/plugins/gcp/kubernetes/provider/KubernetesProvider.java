package com.orchpilot.workflow.plugins.gcp.kubernetes.provider;

import com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesApiClient;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

/**
 * How the plugin reaches a Kubernetes cluster, without knowing whose cloud it is.
 *
 * <h2>Where the vendor boundary actually falls</h2>
 *
 * Only two things differ between GKE, EKS and AKS: how you authenticate, and how you manage the cluster itself.
 * Everything after that — deployments, pods, services, manifests, rollouts — is the same Kubernetes API on every
 * cloud. So the seam is drawn exactly there:
 *
 * <ul>
 *   <li>{@link #connect} resolves a credential and hands back a ready {@link KubernetesApiClient}. That client is a
 *       concrete class, not an interface, because there is genuinely nothing vendor-specific left to vary once the
 *       endpoint and bearer token are known.</li>
 *   <li>{@link #admin} exposes cluster and node-pool lifecycle, which <em>is</em> vendor-specific and therefore
 *       stays an interface.</li>
 * </ul>
 *
 * <h2>What adding EKS or AKS costs</h2>
 *
 * A new {@code KubernetesProvider} plus a new {@link ClusterAdmin}, registered in the plugin's provider map. Every
 * one of the Kubernetes workload node types keeps working against it unchanged, and the workflow engine, the AI
 * Agent, the Plugin Registry and the security model are not touched at all — which is the point of putting this
 * seam inside the plugin rather than in the core.
 */
public interface KubernetesProvider {

    /** @return the stable provider id, e.g. {@code gcp-gke}; surfaced in node output so a workflow can branch */
    String id();

    /** @return a human label for logs and errors, e.g. {@code Google Kubernetes Engine} */
    String displayName();

    /**
     * Resolves the credential named by the node and returns a client bound to the target cluster's API server.
     *
     * @param configuration the node's already variable-resolved configuration
     * @param context       the plugin context, the only route to secrets and HTTP
     * @param timeoutMillis the engine's per-node timeout, applied to every request the client makes
     * @throws com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesException when the cluster cannot be
     *         reached over a trusted connection
     */
    KubernetesApiClient connect(NodeConfiguration configuration, PluginContext context, long timeoutMillis);

    /**
     * Resolves the credential and returns the cluster-lifecycle API.
     *
     * @return the admin surface, never null for a provider that manages clusters
     */
    ClusterAdmin admin(NodeConfiguration configuration, PluginContext context, long timeoutMillis);
}
