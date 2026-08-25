package com.orchpilot.workflow.plugins.gcp.kubernetes.model;

/**
 * The Kubernetes resource kinds this plugin addresses, and where each lives in the API.
 *
 * <h2>Why a table instead of a class per resource</h2>
 *
 * The Kubernetes API is uniform: every namespaced resource is
 * {@code {apiRoot}/namespaces/{ns}/{plural}[/{name}]} and every operation on it is the same five verbs. Encoding
 * the only things that actually differ — the API group and the plural name — as data means one generic client
 * serves Deployments, Pods, Services, Jobs, Ingresses and everything else, instead of a dozen near-identical
 * service classes. Adding CronJobs or HPAs is a line here, not a new class.
 */
public enum K8sResource {

    // Core API group ("" — the legacy path /api/v1)
    POD("Pod", "/api/v1", "pods", true),
    SERVICE("Service", "/api/v1", "services", true),
    CONFIGMAP("ConfigMap", "/api/v1", "configmaps", true),
    SECRET("Secret", "/api/v1", "secrets", true),
    NAMESPACE("Namespace", "/api/v1", "namespaces", false),
    EVENT("Event", "/api/v1", "events", true),
    NODE("Node", "/api/v1", "nodes", false),
    SERVICE_ACCOUNT("ServiceAccount", "/api/v1", "serviceaccounts", true),
    PERSISTENT_VOLUME_CLAIM("PersistentVolumeClaim", "/api/v1", "persistentvolumeclaims", true),

    // apps/v1
    DEPLOYMENT("Deployment", "/apis/apps/v1", "deployments", true),
    STATEFULSET("StatefulSet", "/apis/apps/v1", "statefulsets", true),
    DAEMONSET("DaemonSet", "/apis/apps/v1", "daemonsets", true),
    REPLICASET("ReplicaSet", "/apis/apps/v1", "replicasets", true),

    // batch/v1
    JOB("Job", "/apis/batch/v1", "jobs", true),
    CRONJOB("CronJob", "/apis/batch/v1", "cronjobs", true),

    // networking.k8s.io/v1
    INGRESS("Ingress", "/apis/networking.k8s.io/v1", "ingresses", true),
    NETWORK_POLICY("NetworkPolicy", "/apis/networking.k8s.io/v1", "networkpolicies", true),

    // autoscaling/v2
    HPA("HorizontalPodAutoscaler", "/apis/autoscaling/v2", "horizontalpodautoscalers", true);

    private final String kind;
    private final String apiRoot;
    private final String plural;
    private final boolean namespaced;

    K8sResource(String kind, String apiRoot, String plural, boolean namespaced) {
        this.kind = kind;
        this.apiRoot = apiRoot;
        this.plural = plural;
        this.namespaced = namespaced;
    }

    /** @return the {@code kind} as it appears in a manifest, e.g. {@code Deployment} */
    public String kind() {
        return kind;
    }

    public String apiRoot() {
        return apiRoot;
    }

    public String plural() {
        return plural;
    }

    /** @return whether the resource lives in a namespace; a Namespace or Node does not */
    public boolean namespaced() {
        return namespaced;
    }

    /** @return the {@code apiVersion} a manifest for this kind must declare */
    public String apiVersion() {
        return "/api/v1".equals(apiRoot) ? "v1" : apiRoot.substring("/apis/".length());
    }

    /**
     * Builds the collection or item path.
     *
     * @param namespace namespace, ignored for cluster-scoped resources
     * @param name      resource name, or null for the collection
     */
    public String path(String namespace, String name) {
        StringBuilder path = new StringBuilder(apiRoot);
        if (namespaced) {
            path.append("/namespaces/").append(namespace == null || namespace.isBlank() ? "default" : namespace);
        }
        path.append('/').append(plural);
        if (name != null && !name.isBlank()) {
            path.append('/').append(name);
        }
        return path.toString();
    }

    /** @return the resource matching a manifest's {@code kind}, or null when it is one this plugin cannot apply */
    public static K8sResource forKind(String kind) {
        if (kind == null) {
            return null;
        }
        for (K8sResource resource : values()) {
            if (resource.kind.equalsIgnoreCase(kind.trim())) {
                return resource;
            }
        }
        return null;
    }
}
