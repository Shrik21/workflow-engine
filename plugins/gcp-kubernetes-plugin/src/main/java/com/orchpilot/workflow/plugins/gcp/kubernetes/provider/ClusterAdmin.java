package com.orchpilot.workflow.plugins.gcp.kubernetes.provider;

import java.util.List;
import java.util.Map;

/**
 * Cluster and node-pool lifecycle — the genuinely vendor-specific half of Kubernetes management.
 *
 * <h2>Deliberately shaped around what workflows need, not around one cloud's API</h2>
 *
 * The vocabulary here — scope, location, cluster, node pool — is common to GKE, EKS and AKS even though each spells
 * it differently (project/region, account/region, resource-group/region). Returning plain maps rather than a typed
 * model is intentional: workflow outputs are maps anyway, and a typed model would have to be the union of three
 * clouds' cluster shapes, which is a worse abstraction than none.
 *
 * <p>Every mutating method returns the cloud's long-running-operation record where there is one, so the caller can
 * report it and a workflow can poll or simply move on.
 */
public interface ClusterAdmin {

    /** Identifies a cluster within a cloud account. {@code location} is a region or a zone. */
    record ClusterRef(String scope, String location, String cluster) {

        /** @return the location segment as the cloud's API wants it, defaulting to every location */
        public String locationOrWildcard() {
            return location == null || location.isBlank() ? "-" : location;
        }
    }

    /** @return one map per cluster, already flattened to the fields a workflow branches on */
    List<Map<String, Object>> listClusters(ClusterRef ref);

    /** @return the cluster's full record as the cloud returns it */
    Map<String, Object> getCluster(ClusterRef ref);

    /**
     * Creates a cluster.
     *
     * @param spec the plugin's normalised creation request (name, node count, machine type, network, …)
     * @return the long-running operation record
     */
    Map<String, Object> createCluster(ClusterRef ref, Map<String, Object> spec);

    /** @return the long-running operation record for the deletion */
    Map<String, Object> deleteCluster(ClusterRef ref);

    /** @return one map per node pool */
    List<Map<String, Object>> listNodePools(ClusterRef ref);

    /** @return the long-running operation record for the resize */
    Map<String, Object> scaleNodePool(ClusterRef ref, String nodePool, int nodeCount);

    /** @return the long-running operation record for the deletion */
    Map<String, Object> deleteNodePool(ClusterRef ref, String nodePool);
}
