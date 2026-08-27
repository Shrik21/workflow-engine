package com.orchpilot.workflow.plugins.gcp.kubernetes.model;

/**
 * Every capability this plugin exposes, across both layers — GKE control plane and Kubernetes workloads.
 *
 * <h2>Two layers, one catalogue</h2>
 *
 * {@link Layer#GKE} operations talk to {@code container.googleapis.com} and are Google-specific: clusters and
 * node pools. {@link Layer#KUBERNETES} operations talk to the cluster's own API server and are not Google-specific
 * at all — which is exactly why they sit behind a provider abstraction, so an EKS or AKS implementation later
 * reuses every one of them unchanged.
 *
 * <h2>Risk drives approval</h2>
 *
 * The platform's node contract carries a boolean {@code destructive}, so {@link RiskLevel#HIGH} and
 * {@link RiskLevel#VERY_HIGH} both map onto it and a supervised agent needs approval for either. The finer grade
 * is kept here and published in the manifest, because "delete a deployment" and "delete a cluster" deserve
 * different policy even though both need a human.
 */
public enum KubernetesOperation {

    // ================================================================ GKE control plane
    CLUSTER_LIST("GKE_LIST_CLUSTERS", "List GKE Clusters", "gke.cluster.list", Layer.GKE,
            "Lists the GKE clusters in a project and location.", RiskLevel.READ_ONLY),
    CLUSTER_GET("GKE_GET_CLUSTER", "Get GKE Cluster", "gke.cluster.get", Layer.GKE,
            "Reads one cluster: version, endpoint, node count and status.", RiskLevel.READ_ONLY),
    CLUSTER_HEALTH("GKE_CLUSTER_HEALTH", "GKE Cluster Health", "gke.cluster.health", Layer.GKE,
            "Summarises whether a cluster and its node pools are healthy.", RiskLevel.READ_ONLY),
    CLUSTER_CREATE("GKE_CREATE_CLUSTER", "Create GKE Cluster", "gke.cluster.create", Layer.GKE,
            "Creates a GKE cluster. Long-running; returns the operation to poll.", RiskLevel.HIGH),
    CLUSTER_DELETE("GKE_DELETE_CLUSTER", "Delete GKE Cluster", "gke.cluster.delete", Layer.GKE,
            "Permanently deletes a cluster and everything running on it. Irreversible.", RiskLevel.VERY_HIGH),
    NODEPOOL_LIST("GKE_LIST_NODE_POOLS", "List GKE Node Pools", "gke.nodePool.list", Layer.GKE,
            "Lists a cluster's node pools.", RiskLevel.READ_ONLY),
    NODEPOOL_SCALE("GKE_SCALE_NODE_POOL", "Scale GKE Node Pool", "gke.nodePool.scale", Layer.GKE,
            "Changes a node pool's node count.", RiskLevel.MEDIUM),
    NODEPOOL_DELETE("GKE_DELETE_NODE_POOL", "Delete GKE Node Pool", "gke.nodePool.delete", Layer.GKE,
            "Deletes a node pool and drains its nodes. Irreversible.", RiskLevel.HIGH),

    // ================================================================ Kubernetes workloads
    NAMESPACE_LIST("K8S_LIST_NAMESPACES", "List Kubernetes Namespaces", "kubernetes.namespace.list",
            Layer.KUBERNETES, "Lists the cluster's namespaces.", RiskLevel.READ_ONLY),
    NAMESPACE_CREATE("K8S_CREATE_NAMESPACE", "Create Kubernetes Namespace", "kubernetes.namespace.create",
            Layer.KUBERNETES, "Creates a namespace.", RiskLevel.MEDIUM),
    NAMESPACE_DELETE("K8S_DELETE_NAMESPACE", "Delete Kubernetes Namespace", "kubernetes.namespace.delete",
            Layer.KUBERNETES, "Deletes a namespace and everything in it. Irreversible.", RiskLevel.HIGH),

    POD_LIST("K8S_LIST_PODS", "List Kubernetes Pods", "kubernetes.pod.list", Layer.KUBERNETES,
            "Lists pods in a namespace, with their phase, restarts and readiness.", RiskLevel.READ_ONLY),
    POD_GET("K8S_GET_POD", "Get Kubernetes Pod", "kubernetes.pod.get", Layer.KUBERNETES,
            "Reads one pod, including container statuses and why it is not ready.", RiskLevel.READ_ONLY),
    POD_LOGS("K8S_POD_LOGS", "Get Kubernetes Pod Logs", "kubernetes.pod.logs", Layer.KUBERNETES,
            "Reads a pod's logs, bounded by tail lines — what an AI Agent reads to diagnose a failure.",
            RiskLevel.READ_ONLY),
    POD_DELETE("K8S_DELETE_POD", "Delete Kubernetes Pod", "kubernetes.pod.delete", Layer.KUBERNETES,
            "Deletes a pod. Its controller will usually recreate it.", RiskLevel.HIGH),
    POD_EXEC("K8S_POD_EXEC", "Execute In Kubernetes Pod", "kubernetes.pod.exec", Layer.KUBERNETES,
            "Not available: exec needs a streaming protocol upgrade the plugin transport does not carry. "
                    + "Refused explicitly and audited.", RiskLevel.HIGH),

    DEPLOYMENT_LIST("K8S_LIST_DEPLOYMENTS", "List Kubernetes Deployments", "kubernetes.deployment.list",
            Layer.KUBERNETES, "Lists deployments with their replica counts.", RiskLevel.READ_ONLY),
    DEPLOYMENT_GET("K8S_GET_DEPLOYMENT", "Get Kubernetes Deployment", "kubernetes.deployment.get",
            Layer.KUBERNETES, "Reads one deployment.", RiskLevel.READ_ONLY),
    DEPLOYMENT_HEALTH("K8S_DEPLOYMENT_HEALTH", "Kubernetes Deployment Health", "kubernetes.deployment.health",
            Layer.KUBERNETES, "Reports desired, ready, available and updated replicas, and whether the "
            + "deployment is healthy.", RiskLevel.READ_ONLY),
    DEPLOYMENT_CREATE("K8S_CREATE_DEPLOYMENT", "Create Kubernetes Deployment", "kubernetes.deployment.create",
            Layer.KUBERNETES, "Creates a deployment from an image, replica count and optional port.",
            RiskLevel.MEDIUM),
    DEPLOYMENT_UPDATE_IMAGE("K8S_UPDATE_DEPLOYMENT_IMAGE", "Update Kubernetes Deployment Image",
            "kubernetes.deployment.updateImage", Layer.KUBERNETES,
            "Rolls a deployment onto a new image, optionally waiting for the rollout.", RiskLevel.MEDIUM),
    DEPLOYMENT_SCALE("K8S_SCALE_DEPLOYMENT", "Scale Kubernetes Deployment", "kubernetes.deployment.scale",
            Layer.KUBERNETES, "Changes a deployment's replica count.", RiskLevel.LOW),
    DEPLOYMENT_RESTART("K8S_RESTART_DEPLOYMENT", "Restart Kubernetes Deployment",
            "kubernetes.deployment.restart", Layer.KUBERNETES,
            "Rolling-restarts a deployment, the same as kubectl rollout restart.", RiskLevel.LOW),
    DEPLOYMENT_ROLLBACK("K8S_ROLLBACK_DEPLOYMENT", "Rollback Kubernetes Deployment",
            "kubernetes.deployment.rollback", Layer.KUBERNETES,
            "Rolls a deployment back to its previous ReplicaSet's pod template.", RiskLevel.MEDIUM),
    DEPLOYMENT_DELETE("K8S_DELETE_DEPLOYMENT", "Delete Kubernetes Deployment", "kubernetes.deployment.delete",
            Layer.KUBERNETES, "Deletes a deployment and its pods. Irreversible.", RiskLevel.HIGH),

    SERVICE_LIST("K8S_LIST_SERVICES", "List Kubernetes Services", "kubernetes.service.list", Layer.KUBERNETES,
            "Lists services with their type and cluster IP.", RiskLevel.READ_ONLY),
    SERVICE_GET("K8S_GET_SERVICE", "Get Kubernetes Service", "kubernetes.service.get", Layer.KUBERNETES,
            "Reads one service, including any external address.", RiskLevel.READ_ONLY),
    SERVICE_DELETE("K8S_DELETE_SERVICE", "Delete Kubernetes Service", "kubernetes.service.delete",
            Layer.KUBERNETES, "Deletes a service. Irreversible.", RiskLevel.HIGH),

    CONFIGMAP_LIST("K8S_LIST_CONFIGMAPS", "List Kubernetes ConfigMaps", "kubernetes.configmap.list",
            Layer.KUBERNETES, "Lists ConfigMaps.", RiskLevel.READ_ONLY),
    CONFIGMAP_GET("K8S_GET_CONFIGMAP", "Get Kubernetes ConfigMap", "kubernetes.configmap.get",
            Layer.KUBERNETES, "Reads a ConfigMap's data.", RiskLevel.READ_ONLY),
    CONFIGMAP_DELETE("K8S_DELETE_CONFIGMAP", "Delete Kubernetes ConfigMap", "kubernetes.configmap.delete",
            Layer.KUBERNETES, "Deletes a ConfigMap. Irreversible.", RiskLevel.HIGH),

    SECRET_LIST("K8S_LIST_SECRETS", "List Kubernetes Secrets", "kubernetes.secret.list", Layer.KUBERNETES,
            "Lists secret names and types only — values are never returned.", RiskLevel.READ_ONLY),
    SECRET_DELETE("K8S_DELETE_SECRET", "Delete Kubernetes Secret", "kubernetes.secret.delete",
            Layer.KUBERNETES, "Deletes a secret. Irreversible.", RiskLevel.HIGH),

    JOB_LIST("K8S_LIST_JOBS", "List Kubernetes Jobs", "kubernetes.job.list", Layer.KUBERNETES,
            "Lists Jobs with their completion state.", RiskLevel.READ_ONLY),
    JOB_DELETE("K8S_DELETE_JOB", "Delete Kubernetes Job", "kubernetes.job.delete", Layer.KUBERNETES,
            "Deletes a Job. Irreversible.", RiskLevel.HIGH),
    CRONJOB_LIST("K8S_LIST_CRONJOBS", "List Kubernetes CronJobs", "kubernetes.cronjob.list", Layer.KUBERNETES,
            "Lists CronJobs with their schedules.", RiskLevel.READ_ONLY),

    STATEFULSET_LIST("K8S_LIST_STATEFULSETS", "List Kubernetes StatefulSets", "kubernetes.statefulset.list",
            Layer.KUBERNETES, "Lists StatefulSets with their replica counts.", RiskLevel.READ_ONLY),
    STATEFULSET_SCALE("K8S_SCALE_STATEFULSET", "Scale Kubernetes StatefulSet", "kubernetes.statefulset.scale",
            Layer.KUBERNETES, "Changes a StatefulSet's replica count.", RiskLevel.LOW),

    DAEMONSET_LIST("K8S_LIST_DAEMONSETS", "List Kubernetes DaemonSets", "kubernetes.daemonset.list",
            Layer.KUBERNETES, "Lists DaemonSets.", RiskLevel.READ_ONLY),
    INGRESS_LIST("K8S_LIST_INGRESSES", "List Kubernetes Ingresses", "kubernetes.ingress.list",
            Layer.KUBERNETES, "Lists Ingresses with their hosts and addresses.", RiskLevel.READ_ONLY),
    HPA_LIST("K8S_LIST_HPAS", "List Kubernetes Autoscalers", "kubernetes.hpa.list", Layer.KUBERNETES,
            "Lists HorizontalPodAutoscalers with their current and target replicas.", RiskLevel.READ_ONLY),
    EVENT_LIST("K8S_LIST_EVENTS", "List Kubernetes Events", "kubernetes.event.list", Layer.KUBERNETES,
            "Lists recent events in a namespace — usually where the real reason for a failure is.",
            RiskLevel.READ_ONLY),

    MANIFEST_VALIDATE("K8S_VALIDATE_MANIFEST", "Validate Kubernetes Manifest", "kubernetes.manifest.validate",
            Layer.KUBERNETES, "Parses and checks a YAML or JSON manifest without touching the cluster. Also "
            + "runs a server-side dry run when asked.", RiskLevel.READ_ONLY),
    MANIFEST_APPLY("K8S_APPLY_MANIFEST", "Apply Kubernetes Manifest", "kubernetes.manifest.apply",
            Layer.KUBERNETES, "Applies a validated manifest, creating or updating the resource. Supports a "
            + "dry run that changes nothing.", RiskLevel.MEDIUM),
    MANIFEST_DELETE("K8S_DELETE_MANIFEST", "Delete Kubernetes Manifest Resource", "kubernetes.manifest.delete",
            Layer.KUBERNETES, "Deletes the resource a manifest describes. Irreversible.", RiskLevel.HIGH);

    /** Which API a capability talks to, and therefore which client serves it. */
    public enum Layer {
        /** Google-specific: {@code container.googleapis.com}. */
        GKE,
        /** Vendor-neutral: the cluster's own API server. Reusable by a future EKS or AKS provider. */
        KUBERNETES
    }

    public enum RiskLevel {
        READ_ONLY,
        LOW,
        MEDIUM,
        HIGH,
        VERY_HIGH
    }

    private final String nodeType;
    private final String displayName;
    private final String capability;
    private final Layer layer;
    private final String description;
    private final RiskLevel risk;

    KubernetesOperation(String nodeType, String displayName, String capability, Layer layer, String description,
                        RiskLevel risk) {
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.capability = capability;
        this.layer = layer;
        this.description = description;
        this.risk = risk;
    }

    public String nodeType() {
        return nodeType;
    }

    public String displayName() {
        return displayName;
    }

    public String capability() {
        return capability;
    }

    public Layer layer() {
        return layer;
    }

    public String description() {
        return description;
    }

    public RiskLevel risk() {
        return risk;
    }

    /** @return whether a supervised agent must have this approved; both HIGH grades require it */
    public boolean destructive() {
        return risk == RiskLevel.HIGH || risk == RiskLevel.VERY_HIGH;
    }

    public static KubernetesOperation forNodeType(String nodeType) {
        for (KubernetesOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
