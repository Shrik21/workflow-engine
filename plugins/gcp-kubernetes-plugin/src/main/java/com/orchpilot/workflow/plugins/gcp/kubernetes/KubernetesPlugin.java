package com.orchpilot.workflow.plugins.gcp.kubernetes;

import com.orchpilot.workflow.plugins.gcp.kubernetes.client.GoogleTokenSource;
import com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesApiClient;
import com.orchpilot.workflow.plugins.gcp.kubernetes.client.KubernetesException;
import com.orchpilot.workflow.plugins.gcp.kubernetes.manifest.ManifestParser;
import com.orchpilot.workflow.plugins.gcp.kubernetes.manifest.Workloads;
import com.orchpilot.workflow.plugins.gcp.kubernetes.model.K8sResource;
import com.orchpilot.workflow.plugins.gcp.kubernetes.model.KubernetesOperation;
import com.orchpilot.workflow.plugins.gcp.kubernetes.model.Summaries;
import com.orchpilot.workflow.plugins.gcp.kubernetes.provider.ClusterAdmin;
import com.orchpilot.workflow.plugins.gcp.kubernetes.provider.KubernetesProvider;
import com.orchpilot.workflow.plugins.gcp.kubernetes.provider.gke.GkeKubernetesProvider;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * GKE cluster management and Kubernetes workload operations as OrchPilot workflow nodes.
 *
 * <h2>How this stays inside the existing platform</h2>
 *
 * Nothing here duplicates the platform. There is no second AI Agent, plugin server, registry, security system or
 * workflow engine — this is one {@link WorkflowNodePlugin} that reaches the outside world only through the SDK:
 * the engine's allow-listed HTTP client, its audited secret provider, and its data store. Configuration arrives
 * already variable-resolved by the engine's own resolver, and every authorization decision stays the engine's.
 *
 * <h2>The AI Agent boundary</h2>
 *
 * Nodes declare {@code supportsAI}, so the agent discovers them through the existing Plugin Registry as ordinary
 * tools. What it discovers is a fixed catalogue of typed capabilities — never a shell, never a kubectl passthrough,
 * never a raw request builder. The path is always <em>agent → capability → permission → policy → Kubernetes API</em>:
 * the agent picks a tool and supplies parameters, the engine authorises it, destructive tools require approval, and
 * only then does this plugin resolve a credential the agent never sees.
 *
 * <p>Thread-safe: the only mutable state is the token cache inside {@link GoogleTokenSource}, which is concurrent.
 */
public class KubernetesPlugin implements WorkflowNodePlugin {

    private static final String PLUGIN_ID = "orchpilot-gcp-kubernetes";
    private static final String PLUGIN_VERSION = "1.0.3";
    private static final String GKE_CATEGORY = "GCP Kubernetes";
    private static final String K8S_CATEGORY = "Kubernetes";

    private final GoogleTokenSource tokens = new GoogleTokenSource();

    /**
     * The registered providers, by id.
     *
     * <p>A map rather than a single field because this is the extension point: adding EKS means putting an
     * {@code EksKubernetesProvider} in here, and every Kubernetes workload node then works against it unchanged.
     */
    private final Map<String, KubernetesProvider> providers = new LinkedHashMap<>();

    private volatile PluginContext context;

    public KubernetesPlugin() {
        KubernetesProvider gke = new GkeKubernetesProvider(tokens);
        providers.put(gke.id(), gke);
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "GCP Kubernetes";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Manage GKE clusters and node pools, and deploy, scale, inspect and roll back Kubernetes workloads.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("GCP Kubernetes plugin initialised with providers {}", providers.keySet());
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("GCP Kubernetes plugin destroyed");
        }
    }

    // ------------------------------------------------------------------ node catalogue

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>(KubernetesOperation.values().length);
        for (KubernetesOperation operation : KubernetesOperation.values()) {
            boolean readOnly = operation.risk() == KubernetesOperation.RiskLevel.READ_ONLY;
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .description(operation.description() + " [capability: " + operation.capability() + "]")
                    .category(operation.layer() == KubernetesOperation.Layer.GKE ? GKE_CATEGORY : K8S_CATEGORY)
                    .icon(operation.layer() == KubernetesOperation.Layer.GKE ? "cloud" : "layers")
                    .configurationSchema(NodeSchemas.forOperation(operation))
                    .outputVariables("success", "operation", "provider", "projectId", "location", "clusterName",
                            "namespace", "resourceName", "status", "count", "items", "healthy")
                    // A read is safely repeatable; a create, scale or delete is not, so the engine guards resumes.
                    .idempotent(readOnly)
                    .supportsRetry(true)
                    .supportsAI(true)
                    .destructive(operation.destructive())
                    .build());
        }
        return definitions;
    }

    // ------------------------------------------------------------------ execution

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        KubernetesOperation operation = KubernetesOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("K8S_UNKNOWN_OPERATION",
                    "Unknown Kubernetes node type: " + executionContext.nodeType());
        }
        NodeConfiguration cfg = executionContext.configuration();
        KubernetesProvider provider = providers.get(GkeKubernetesProvider.ID);

        try {
            // Checked before the confirmation gate: exec is unavailable whether or not anyone confirms it, so
            // reporting "not confirmed" would send an author looking for an approval that would not help.
            if (operation == KubernetesOperation.POD_EXEC) {
                audit(executionContext, operation, cfg, "REFUSED_UNSUPPORTED");
                return execRefused(cfg);
            }

            NodeExecutionResult confirmation = checkConfirmation(operation, cfg);
            if (confirmation != null) {
                audit(executionContext, operation, cfg, "REFUSED_UNCONFIRMED");
                return confirmation;
            }

            NodeExecutionResult result = operation.layer() == KubernetesOperation.Layer.GKE
                    ? executeGke(executionContext, operation, cfg, provider)
                    : executeKubernetes(executionContext, operation, cfg, provider);
            audit(executionContext, operation, cfg, result.isSuccess() ? "OK" : "FAILED");
            return result;
        } catch (KubernetesException ex) {
            context.logger().warn("Kubernetes {} failed: {} ({})", operation, ex.errorCode(), ex.getMessage());
            audit(executionContext, operation, cfg, "FAILED");
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure("K8S_MISCONFIGURED", ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ GKE control plane

    private NodeExecutionResult executeGke(NodeExecutionContext ctx, KubernetesOperation operation,
                                           NodeConfiguration cfg, KubernetesProvider provider) {
        GkeKubernetesProvider gke = (GkeKubernetesProvider) provider;
        String project = gke.resolveProject(cfg, context);
        ClusterAdmin admin = provider.admin(cfg, context, ctx.timeoutMillis());
        ClusterAdmin.ClusterRef ref = new ClusterAdmin.ClusterRef(project,
                cfg.getString("location", null), cfg.getString("clusterName", null));

        return switch (operation) {
            case CLUSTER_LIST -> {
                List<Map<String, Object>> clusters = admin.listClusters(ref);
                yield success(operation, ref, null, outputs -> {
                    outputs.put("count", clusters.size());
                    outputs.put("items", map(clusters, Summaries::cluster));
                });
            }
            case CLUSTER_GET -> {
                Map<String, Object> cluster = admin.getCluster(requireCluster(ref));
                yield success(operation, ref, null, outputs -> {
                    outputs.putAll(Summaries.cluster(cluster));
                    outputs.put("status", cluster.get("status"));
                    outputs.put("cluster", cluster);
                });
            }
            case CLUSTER_HEALTH -> clusterHealth(operation, admin, requireCluster(ref));
            case CLUSTER_CREATE -> {
                Map<String, Object> operationRecord = admin.createCluster(requireCluster(ref), clusterSpec(cfg, ref));
                yield success(operation, ref, null, outputs -> {
                    outputs.put("status", "CREATING");
                    outputs.put("operationId", operationRecord.get("name"));
                    outputs.put("message", "Cluster creation started. Poll with a Get GKE Cluster node — "
                            + "this typically takes several minutes.");
                });
            }
            case CLUSTER_DELETE -> {
                Map<String, Object> operationRecord = admin.deleteCluster(requireCluster(ref));
                yield success(operation, ref, null, outputs -> {
                    outputs.put("status", "DELETING");
                    outputs.put("operationId", operationRecord.get("name"));
                });
            }
            case NODEPOOL_LIST -> {
                List<Map<String, Object>> pools = admin.listNodePools(requireCluster(ref));
                yield success(operation, ref, null, outputs -> {
                    outputs.put("count", pools.size());
                    outputs.put("items", map(pools, Summaries::nodePool));
                });
            }
            case NODEPOOL_SCALE -> {
                String pool = cfg.requireString("nodePoolName");
                int nodeCount = (int) cfg.getLong("nodeCount", -1);
                if (nodeCount < 0) {
                    throw new PluginConfigurationException("'nodeCount' must be zero or greater.");
                }
                Map<String, Object> operationRecord = admin.scaleNodePool(requireCluster(ref), pool, nodeCount);
                yield success(operation, ref, null, outputs -> {
                    outputs.put("resourceName", pool);
                    outputs.put("nodeCount", nodeCount);
                    outputs.put("status", "SCALING");
                    outputs.put("operationId", operationRecord.get("name"));
                });
            }
            case NODEPOOL_DELETE -> {
                String pool = cfg.requireString("nodePoolName");
                Map<String, Object> operationRecord = admin.deleteNodePool(requireCluster(ref), pool);
                yield success(operation, ref, null, outputs -> {
                    outputs.put("resourceName", pool);
                    outputs.put("status", "DELETING");
                    outputs.put("operationId", operationRecord.get("name"));
                });
            }
            default -> NodeExecutionResult.failure("K8S_UNKNOWN_OPERATION",
                    "Unhandled GKE operation: " + operation);
        };
    }

    /**
     * Summarises whether a cluster is usable.
     *
     * <p>A cluster's own {@code status} is not enough: it reads {@code RUNNING} while a node pool is degraded or
     * mid-repair. Health here means the cluster is RUNNING <em>and</em> every node pool is too, which is the
     * question a workflow gate is actually asking before it deploys onto it.
     */
    private NodeExecutionResult clusterHealth(KubernetesOperation operation, ClusterAdmin admin,
                                              ClusterAdmin.ClusterRef ref) {
        Map<String, Object> cluster = admin.getCluster(ref);
        List<Map<String, Object>> pools = admin.listNodePools(ref);

        String clusterStatus = Summaries.string(cluster, "status");
        List<String> unhealthy = new ArrayList<>();
        for (Map<String, Object> pool : pools) {
            String status = Summaries.string(pool, "status");
            if (!"RUNNING".equals(status)) {
                unhealthy.add(Summaries.string(pool, "name") + "=" + status);
            }
        }
        boolean healthy = "RUNNING".equals(clusterStatus) && unhealthy.isEmpty();

        return success(operation, ref, null, outputs -> {
            outputs.putAll(Summaries.cluster(cluster));
            outputs.put("status", clusterStatus);
            outputs.put("healthy", healthy);
            outputs.put("nodePoolCount", pools.size());
            outputs.put("unhealthyNodePools", unhealthy);
            outputs.put("items", map(pools, Summaries::nodePool));
        });
    }

    private Map<String, Object> clusterSpec(NodeConfiguration cfg, ClusterAdmin.ClusterRef ref) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", ref.cluster());
        if (cfg.getBoolean("enableAutopilot", false)) {
            // Autopilot owns the node configuration, so sending node fields alongside it is rejected by GKE.
            Map<String, Object> autopilot = new LinkedHashMap<>();
            autopilot.put("enabled", true);
            spec.put("autopilot", autopilot);
        } else {
            spec.put("initialNodeCount", (int) cfg.getLong("initialNodeCount", 3));
            Map<String, Object> nodeConfig = new LinkedHashMap<>();
            nodeConfig.put("machineType", cfg.getString("machineType", "e2-medium"));
            nodeConfig.put("diskSizeGb", (int) cfg.getLong("diskSizeGb", 100));
            spec.put("nodeConfig", nodeConfig);
        }
        String network = cfg.getString("network", null);
        if (network != null && !network.isBlank()) {
            spec.put("network", network);
        }
        String subnetwork = cfg.getString("subnetwork", null);
        if (subnetwork != null && !subnetwork.isBlank()) {
            spec.put("subnetwork", subnetwork);
        }
        String channel = cfg.getString("releaseChannel", null);
        if (channel != null && !channel.isBlank()) {
            Map<String, Object> releaseChannel = new LinkedHashMap<>();
            releaseChannel.put("channel", channel.toUpperCase(java.util.Locale.ROOT));
            spec.put("releaseChannel", releaseChannel);
        }
        return spec;
    }

    // ------------------------------------------------------------------ Kubernetes workloads

    private NodeExecutionResult executeKubernetes(NodeExecutionContext ctx, KubernetesOperation operation,
                                                  NodeConfiguration cfg, KubernetesProvider provider) {
        // Structural manifest validation is the one operation that needs no cluster at all.
        if (operation == KubernetesOperation.MANIFEST_VALIDATE && !cfg.getBoolean("serverDryRun", false)) {
            return validateManifest(operation, cfg, null);
        }

        KubernetesApiClient k8s = provider.connect(cfg, context, ctx.timeoutMillis());
        String namespace = cfg.getString("namespace", "default");
        ClusterAdmin.ClusterRef ref = new ClusterAdmin.ClusterRef(cfg.getString("projectId", null),
                cfg.getString("location", null), cfg.getString("clusterName", null));

        return switch (operation) {
            case NAMESPACE_LIST -> listing(operation, ref, null, k8s, K8sResource.NAMESPACE, null, cfg,
                    Summaries::namespaceSummary);
            case NAMESPACE_CREATE -> {
                String name = cfg.requireString("namespace");
                Map<String, Object> created = k8s.create(K8sResource.NAMESPACE, null,
                        Workloads.namespace(name, Workloads.keyValues(cfg.getString("labels", null))), false);
                yield success(operation, ref, name, outputs -> {
                    outputs.put("resourceName", name);
                    outputs.putAll(Summaries.namespaceSummary(created));
                });
            }
            case NAMESPACE_DELETE -> {
                String name = cfg.requireString("namespace");
                k8s.delete(K8sResource.NAMESPACE, null, name, "Foreground");
                yield deleted(operation, ref, name, name);
            }

            case POD_LIST -> listing(operation, ref, namespace, k8s, K8sResource.POD, namespace, cfg,
                    Summaries::pod);
            case POD_GET -> {
                String name = cfg.requireString("podName");
                Map<String, Object> pod = k8s.get(K8sResource.POD, namespace, name);
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(Summaries.pod(pod));
                    outputs.put("resourceName", name);
                    outputs.put("pod", pod);
                });
            }
            case POD_LOGS -> {
                String name = cfg.requireString("podName");
                String logs = k8s.logs(namespace, name, cfg.getString("container", null),
                        (int) cfg.getLong("tailLines", 200), cfg.getBoolean("previous", false),
                        cfg.getBoolean("timestamps", false));
                yield success(operation, ref, namespace, outputs -> {
                    outputs.put("resourceName", name);
                    outputs.put("logs", logs);
                    outputs.put("lineCount", logs.isEmpty() ? 0 : logs.split("\\R", -1).length);
                });
            }
            case POD_DELETE -> {
                String name = cfg.requireString("podName");
                k8s.delete(K8sResource.POD, namespace, name, null);
                yield deleted(operation, ref, namespace, name);
            }

            case DEPLOYMENT_LIST -> listing(operation, ref, namespace, k8s, K8sResource.DEPLOYMENT, namespace, cfg,
                    Summaries::deployment);
            case DEPLOYMENT_GET -> {
                String name = cfg.requireString("deploymentName");
                Map<String, Object> deployment = k8s.get(K8sResource.DEPLOYMENT, namespace, name);
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(Summaries.deployment(deployment));
                    outputs.put("resourceName", name);
                    outputs.put("deployment", deployment);
                });
            }
            case DEPLOYMENT_HEALTH -> {
                String name = cfg.requireString("deploymentName");
                Map<String, Object> deployment = k8s.get(K8sResource.DEPLOYMENT, namespace, name);
                Map<String, Object> summary = Summaries.deployment(deployment);
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(summary);
                    outputs.put("resourceName", name);
                    outputs.put("status", Boolean.TRUE.equals(summary.get("healthy")) ? "HEALTHY" : "DEGRADED");
                });
            }
            case DEPLOYMENT_CREATE -> {
                String name = cfg.requireString("deploymentName");
                k8s.create(K8sResource.DEPLOYMENT, namespace, Workloads.deployment(cfg, name, namespace), false);
                yield afterRollout(ctx, operation, cfg, k8s, ref, namespace, name);
            }
            case DEPLOYMENT_UPDATE_IMAGE -> {
                String name = cfg.requireString("deploymentName");
                String image = cfg.requireString("image");
                String container = cfg.getString("container", null);
                k8s.patch(K8sResource.DEPLOYMENT, namespace, name,
                        Workloads.imagePatch(container == null || container.isBlank() ? name : container, image),
                        "updating the image of Deployment '" + name + "'");
                yield afterRollout(ctx, operation, cfg, k8s, ref, namespace, name);
            }
            case DEPLOYMENT_SCALE -> {
                String name = cfg.requireString("deploymentName");
                int replicas = requireReplicas(cfg);
                Map<String, Object> scaled = k8s.patch(K8sResource.DEPLOYMENT, namespace, name,
                        Workloads.scalePatch(replicas), "scaling Deployment '" + name + "'");
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(Summaries.deployment(scaled));
                    outputs.put("resourceName", name);
                    outputs.put("desiredReplicas", replicas);
                });
            }
            case DEPLOYMENT_RESTART -> {
                String name = cfg.requireString("deploymentName");
                k8s.patch(K8sResource.DEPLOYMENT, namespace, name, Workloads.restartPatch(Instant.now()),
                        "restarting Deployment '" + name + "'");
                yield afterRollout(ctx, operation, cfg, k8s, ref, namespace, name);
            }
            case DEPLOYMENT_ROLLBACK -> rollback(ctx, operation, cfg, k8s, ref, namespace);
            case DEPLOYMENT_DELETE -> {
                String name = cfg.requireString("deploymentName");
                k8s.delete(K8sResource.DEPLOYMENT, namespace, name, "Foreground");
                yield deleted(operation, ref, namespace, name);
            }

            case SERVICE_LIST -> listing(operation, ref, namespace, k8s, K8sResource.SERVICE, namespace, cfg,
                    Summaries::service);
            case SERVICE_GET -> {
                String name = cfg.requireString("serviceName");
                Map<String, Object> service = k8s.get(K8sResource.SERVICE, namespace, name);
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(Summaries.service(service));
                    outputs.put("resourceName", name);
                    outputs.put("service", service);
                });
            }
            case SERVICE_DELETE -> {
                String name = cfg.requireString("serviceName");
                k8s.delete(K8sResource.SERVICE, namespace, name, null);
                yield deleted(operation, ref, namespace, name);
            }

            case CONFIGMAP_LIST -> listing(operation, ref, namespace, k8s, K8sResource.CONFIGMAP, namespace, cfg,
                    configMap -> Summaries.configMap(configMap, false));
            case CONFIGMAP_GET -> {
                String name = cfg.requireString("configMapName");
                Map<String, Object> configMap = k8s.get(K8sResource.CONFIGMAP, namespace, name);
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(Summaries.configMap(configMap, true));
                    outputs.put("resourceName", name);
                });
            }
            case CONFIGMAP_DELETE -> {
                String name = cfg.requireString("configMapName");
                k8s.delete(K8sResource.CONFIGMAP, namespace, name, null);
                yield deleted(operation, ref, namespace, name);
            }

            // Secrets: names and key names only, never values — there is deliberately no Get Secret node.
            case SECRET_LIST -> listing(operation, ref, namespace, k8s, K8sResource.SECRET, namespace, cfg,
                    Summaries::secret);
            case SECRET_DELETE -> {
                String name = cfg.requireString("secretName");
                k8s.delete(K8sResource.SECRET, namespace, name, null);
                yield deleted(operation, ref, namespace, name);
            }

            case JOB_LIST -> listing(operation, ref, namespace, k8s, K8sResource.JOB, namespace, cfg,
                    Summaries::job);
            case JOB_DELETE -> {
                String name = cfg.requireString("jobName");
                k8s.delete(K8sResource.JOB, namespace, name, "Foreground");
                yield deleted(operation, ref, namespace, name);
            }
            case CRONJOB_LIST -> listing(operation, ref, namespace, k8s, K8sResource.CRONJOB, namespace, cfg,
                    Summaries::cronJob);

            case STATEFULSET_LIST -> listing(operation, ref, namespace, k8s, K8sResource.STATEFULSET, namespace, cfg,
                    Summaries::replicaWorkload);
            case STATEFULSET_SCALE -> {
                String name = cfg.requireString("statefulSetName");
                int replicas = requireReplicas(cfg);
                Map<String, Object> scaled = k8s.patch(K8sResource.STATEFULSET, namespace, name,
                        Workloads.scalePatch(replicas), "scaling StatefulSet '" + name + "'");
                yield success(operation, ref, namespace, outputs -> {
                    outputs.putAll(Summaries.replicaWorkload(scaled));
                    outputs.put("resourceName", name);
                    outputs.put("desiredReplicas", replicas);
                });
            }
            case DAEMONSET_LIST -> listing(operation, ref, namespace, k8s, K8sResource.DAEMONSET, namespace, cfg,
                    Summaries::replicaWorkload);
            case INGRESS_LIST -> listing(operation, ref, namespace, k8s, K8sResource.INGRESS, namespace, cfg,
                    Summaries::ingress);
            case HPA_LIST -> listing(operation, ref, namespace, k8s, K8sResource.HPA, namespace, cfg,
                    Summaries::hpa);
            case EVENT_LIST -> {
                Map<String, Object> response = k8s.list(K8sResource.EVENT, namespace, null,
                        cfg.getString("fieldSelector", null), (int) cfg.getLong("limit", 100));
                List<Map<String, Object>> events = map(KubernetesApiClient.items(response), Summaries::event);
                yield success(operation, ref, namespace, outputs -> {
                    outputs.put("count", events.size());
                    outputs.put("items", events);
                });
            }

            case MANIFEST_VALIDATE -> validateManifest(operation, cfg, k8s);
            case MANIFEST_APPLY -> applyManifest(operation, cfg, k8s, ref, namespace);
            case MANIFEST_DELETE -> deleteManifest(operation, cfg, k8s, ref, namespace);

            default -> NodeExecutionResult.failure("K8S_UNKNOWN_OPERATION",
                    "Unhandled Kubernetes operation: " + operation);
        };
    }

    // ------------------------------------------------------------------ rollout, rollback, manifests

    /**
     * Waits for a Deployment's rollout, when asked, and reports the result either way.
     *
     * <p>Waiting matters because "the patch was accepted" is not "the new version is serving" — an image that does
     * not exist is accepted instantly and then fails in ImagePullBackOff. The wait polls the deployment's status
     * rather than blocking on a watch, so a cancelled execution stops promptly. A rollout that does not finish in
     * time is <em>not</em> reported as success: the node fails with the replica counts, so a workflow can branch
     * onto a rollback.
     */
    private NodeExecutionResult afterRollout(NodeExecutionContext ctx, KubernetesOperation operation,
                                             NodeConfiguration cfg, KubernetesApiClient k8s,
                                             ClusterAdmin.ClusterRef ref, String namespace, String name) {
        if (!cfg.getBoolean("waitForRollout", true)) {
            Map<String, Object> current = k8s.get(K8sResource.DEPLOYMENT, namespace, name);
            return success(operation, ref, namespace, outputs -> {
                outputs.putAll(Summaries.deployment(current));
                outputs.put("resourceName", name);
                outputs.put("status", "ROLLING_OUT");
            });
        }

        long deadline = System.currentTimeMillis() + cfg.getLong("rolloutTimeoutSeconds", 300) * 1000;
        long interval = Math.max(1, cfg.getLong("pollIntervalSeconds", 5)) * 1000;
        Map<String, Object> summary = null;

        while (System.currentTimeMillis() < deadline) {
            if (ctx.isCancelled()) {
                throw new KubernetesException("K8S_CANCELLED",
                        "The execution was cancelled while waiting for the rollout of '" + name + "'.", false);
            }
            summary = Summaries.deployment(k8s.get(K8sResource.DEPLOYMENT, namespace, name));
            if (Boolean.TRUE.equals(summary.get("healthy"))) {
                Map<String, Object> finished = summary;
                return success(operation, ref, namespace, outputs -> {
                    outputs.putAll(finished);
                    outputs.put("resourceName", name);
                    outputs.put("status", "ROLLED_OUT");
                });
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new KubernetesException("K8S_CANCELLED",
                        "Interrupted while waiting for the rollout of '" + name + "'.", false);
            }
        }

        Map<String, Object> last = summary == null ? Map.of() : summary;
        Map<String, Object> outputs = baseOutputs(operation, ref, namespace);
        outputs.putAll(last);
        outputs.put("resourceName", name);
        outputs.put("success", false);
        outputs.put("status", "ROLLOUT_TIMED_OUT");
        return NodeExecutionResult.failure("K8S_ROLLOUT_TIMEOUT",
                "Deployment '" + name + "' did not become healthy within the rollout timeout ("
                        + last.getOrDefault("readyReplicas", 0) + "/" + last.getOrDefault("desiredReplicas", 0)
                        + " replicas ready). Check pod events and logs — an ImagePullBackOff or a failing readiness "
                        + "probe is the usual cause.", false);
    }

    /**
     * Rolls a Deployment back to its previous pod template.
     *
     * <p>Kubernetes keeps history as ReplicaSets, not as a rollback API — the {@code rollback} subresource was
     * removed. So this reconstructs what {@code kubectl rollout undo} does: find the Deployment's ReplicaSets by its
     * selector, take the newest one whose template differs from the live one, and patch that template back.
     */
    private NodeExecutionResult rollback(NodeExecutionContext ctx, KubernetesOperation operation,
                                         NodeConfiguration cfg, KubernetesApiClient k8s,
                                         ClusterAdmin.ClusterRef ref, String namespace) {
        String name = cfg.requireString("deploymentName");
        Map<String, Object> deployment = k8s.get(K8sResource.DEPLOYMENT, namespace, name);
        List<Object> currentImages = Summaries.images(deployment);

        Map<String, Object> selector = Summaries.child(Summaries.child(deployment, "spec"), "selector");
        Map<String, Object> matchLabels = Summaries.child(selector, "matchLabels");
        if (matchLabels == null || matchLabels.isEmpty()) {
            throw new KubernetesException("K8S_ROLLBACK_UNAVAILABLE",
                    "Deployment '" + name + "' has no matchLabels selector, so its ReplicaSet history cannot be "
                            + "found.", false);
        }
        StringBuilder labelSelector = new StringBuilder();
        for (Map.Entry<String, Object> entry : matchLabels.entrySet()) {
            if (!labelSelector.isEmpty()) {
                labelSelector.append(',');
            }
            labelSelector.append(entry.getKey()).append('=').append(entry.getValue());
        }

        List<Map<String, Object>> replicaSets = KubernetesApiClient.items(
                k8s.list(K8sResource.REPLICASET, namespace, labelSelector.toString(), null, 100));

        Map<String, Object> previous = null;
        String previousRevision = null;
        long bestRevision = Long.MIN_VALUE;
        for (Map<String, Object> replicaSet : replicaSets) {
            Map<String, Object> template = Summaries.child(Summaries.child(replicaSet, "spec"), "template");
            if (template == null || Summaries.images(replicaSet).equals(currentImages)) {
                continue; // This is the live revision, not a previous one.
            }
            long revision = revisionOf(replicaSet);
            if (revision > bestRevision) {
                bestRevision = revision;
                previous = template;
                previousRevision = String.valueOf(revision);
            }
        }
        if (previous == null) {
            throw new KubernetesException("K8S_ROLLBACK_UNAVAILABLE",
                    "Deployment '" + name + "' has no earlier revision to roll back to.", false);
        }

        k8s.patch(K8sResource.DEPLOYMENT, namespace, name, Workloads.templatePatch(previous),
                "rolling back Deployment '" + name + "'");
        NodeExecutionResult result = afterRollout(ctx, operation, cfg, k8s, ref, namespace, name);
        if (result.isSuccess()) {
            Map<String, Object> outputs = new LinkedHashMap<>(result.outputs());
            outputs.put("rolledBackToRevision", previousRevision);
            return NodeExecutionResult.success(outputs);
        }
        return result;
    }

    /** @return the ReplicaSet's revision from its annotation, or -1 when it has none */
    private static long revisionOf(Map<String, Object> replicaSet) {
        Map<String, Object> annotations = Summaries.child(Summaries.child(replicaSet, "metadata"), "annotations");
        String revision = Summaries.string(annotations, "deployment.kubernetes.io/revision");
        try {
            return revision == null ? -1 : Long.parseLong(revision.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private NodeExecutionResult validateManifest(KubernetesOperation operation, NodeConfiguration cfg,
                                                 KubernetesApiClient k8s) {
        String namespace = cfg.getString("namespace", "default");
        ManifestParser.Validation validation =
                ManifestParser.validate(cfg.requireString("manifest"), namespace);

        List<Map<String, Object>> described = new ArrayList<>();
        for (ManifestParser.Document document : validation.documents()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", document.resource().kind());
            entry.put("name", document.name());
            entry.put("namespace", document.namespace());
            described.add(entry);
        }

        // A server-side dry run only makes sense once the manifest is structurally sound.
        List<String> problems = new ArrayList<>(validation.problems());
        if (validation.valid() && k8s != null) {
            for (ManifestParser.Document document : validation.documents()) {
                try {
                    k8s.create(document.resource(), document.namespace(), document.body(), true);
                } catch (KubernetesException ex) {
                    if (!"K8S_CONFLICT".equals(ex.errorCode())) {
                        // A conflict means it already exists, which is not a validity problem.
                        problems.add(document.resource().kind() + " '" + document.name() + "': " + ex.getMessage());
                    }
                }
            }
        }

        boolean valid = problems.isEmpty();
        Map<String, Object> outputs = baseOutputs(operation, null, namespace);
        outputs.put("success", true);
        outputs.put("valid", valid);
        outputs.put("count", described.size());
        outputs.put("items", described);
        outputs.put("problems", problems);
        outputs.put("status", valid ? "VALID" : "INVALID");
        // Validation succeeding is not the manifest being valid — the node reports, the workflow decides.
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult applyManifest(KubernetesOperation operation, NodeConfiguration cfg,
                                              KubernetesApiClient k8s, ClusterAdmin.ClusterRef ref,
                                              String namespace) {
        ManifestParser.Validation validation =
                ManifestParser.validate(cfg.requireString("manifest"), namespace);
        if (!validation.valid()) {
            return NodeExecutionResult.failure("K8S_INVALID_MANIFEST",
                    "The manifest is not valid: " + String.join("; ", validation.problems()));
        }
        boolean dryRun = cfg.getBoolean("dryRun", false);

        List<Map<String, Object>> applied = new ArrayList<>();
        for (ManifestParser.Document document : validation.documents()) {
            // Apply means create-or-update: try create, fall back to replace on the conflict that means "exists".
            String action = "created";
            Map<String, Object> result;
            try {
                result = k8s.create(document.resource(), document.namespace(), document.body(), dryRun);
            } catch (KubernetesException ex) {
                if (!"K8S_CONFLICT".equals(ex.errorCode())) {
                    throw ex;
                }
                action = "updated";
                result = k8s.replace(document.resource(), document.namespace(), document.name(),
                        merged(k8s, document), dryRun);
            }
            Map<String, Object> entry = new LinkedHashMap<>(Summaries.generic(result));
            entry.put("kind", document.resource().kind());
            entry.put("action", dryRun ? "validated" : action);
            applied.add(entry);
        }

        Map<String, Object> outputs = baseOutputs(operation, ref, namespace);
        outputs.put("success", true);
        outputs.put("count", applied.size());
        outputs.put("items", applied);
        outputs.put("status", dryRun ? "DRY_RUN" : "APPLIED");
        return NodeExecutionResult.success(outputs);
    }

    /**
     * Prepares a manifest for a replace by carrying over the server's {@code resourceVersion}.
     *
     * <p>A {@code PUT} without it is rejected; with a stale one it is also rejected. Reading the live object
     * immediately before is what makes an apply of an existing resource work, and the rejection on a stale version
     * is optimistic concurrency doing its job rather than something to route around.
     */
    private Map<String, Object> merged(KubernetesApiClient k8s, ManifestParser.Document document) {
        Map<String, Object> live = k8s.get(document.resource(), document.namespace(), document.name());
        Map<String, Object> liveMetadata = Summaries.child(live, "metadata");
        Map<String, Object> body = new LinkedHashMap<>(document.body());
        Map<String, Object> metadata = new LinkedHashMap<>(
                Summaries.child(body, "metadata") == null ? Map.of() : Summaries.child(body, "metadata"));
        metadata.put("resourceVersion", Summaries.string(liveMetadata, "resourceVersion"));
        body.put("metadata", metadata);
        return body;
    }

    private NodeExecutionResult deleteManifest(KubernetesOperation operation, NodeConfiguration cfg,
                                               KubernetesApiClient k8s, ClusterAdmin.ClusterRef ref,
                                               String namespace) {
        ManifestParser.Validation validation =
                ManifestParser.validate(cfg.requireString("manifest"), namespace);
        if (!validation.valid()) {
            return NodeExecutionResult.failure("K8S_INVALID_MANIFEST",
                    "The manifest is not valid: " + String.join("; ", validation.problems()));
        }
        List<Map<String, Object>> removed = new ArrayList<>();
        for (ManifestParser.Document document : validation.documents()) {
            k8s.delete(document.resource(), document.namespace(), document.name(), "Foreground");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", document.resource().kind());
            entry.put("name", document.name());
            entry.put("namespace", document.namespace());
            removed.add(entry);
        }
        Map<String, Object> outputs = baseOutputs(operation, ref, namespace);
        outputs.put("success", true);
        outputs.put("count", removed.size());
        outputs.put("items", removed);
        outputs.put("status", "DELETED");
        return NodeExecutionResult.success(outputs);
    }

    /**
     * The explicit refusal for pod exec.
     *
     * <p>This is not a missing feature to be filled in later. Exec requires a SPDY or WebSocket protocol upgrade,
     * and the engine's plugin transport is request/response only — deliberately, because a plugin that can open
     * arbitrary streams from inside the engine is a very different security proposition. The node exists so the
     * capability is visible and auditable as refused, rather than an author assuming it works and discovering
     * otherwise during an incident.
     */
    private NodeExecutionResult execRefused(NodeConfiguration cfg) {
        context.logger().warn("Pod exec refused for pod '{}' — the capability is not available in this runtime",
                cfg.getString("podName", "?"));
        return NodeExecutionResult.failure("K8S_EXEC_NOT_SUPPORTED",
                "Executing commands inside a pod is not available. It requires a streaming protocol upgrade "
                        + "(SPDY/WebSocket) that the plugin transport does not carry, so no OrchPilot workflow or "
                        + "AI Agent can obtain a shell in a cluster through this plugin. Use Get Pod Logs, List "
                        + "Events and Get Pod for diagnosis, or run the command as a Kubernetes Job.");
    }

    // ------------------------------------------------------------------ shared helpers

    private NodeExecutionResult listing(KubernetesOperation operation, ClusterAdmin.ClusterRef ref,
                                        String outputNamespace, KubernetesApiClient k8s, K8sResource resource,
                                        String namespace, NodeConfiguration cfg,
                                        Function<Map<String, Object>, Map<String, Object>> summariser) {
        Map<String, Object> response = k8s.list(resource, namespace, cfg.getString("labelSelector", null), null,
                (int) cfg.getLong("limit", 200));
        List<Map<String, Object>> items = map(KubernetesApiClient.items(response), summariser);
        return success(operation, ref, outputNamespace, outputs -> {
            outputs.put("count", items.size());
            outputs.put("items", items);
        });
    }

    private NodeExecutionResult deleted(KubernetesOperation operation, ClusterAdmin.ClusterRef ref,
                                        String namespace, String name) {
        return success(operation, ref, namespace, outputs -> {
            outputs.put("resourceName", name);
            outputs.put("status", "DELETED");
        });
    }

    private NodeExecutionResult success(KubernetesOperation operation, ClusterAdmin.ClusterRef ref,
                                        String namespace, java.util.function.Consumer<Map<String, Object>> enrich) {
        Map<String, Object> outputs = baseOutputs(operation, ref, namespace);
        enrich.accept(outputs);
        outputs.putIfAbsent("status", "OK");
        return NodeExecutionResult.success(outputs);
    }

    private Map<String, Object> baseOutputs(KubernetesOperation operation, ClusterAdmin.ClusterRef ref,
                                            String namespace) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation.name());
        outputs.put("provider", GkeKubernetesProvider.ID);
        if (ref != null) {
            outputs.put("projectId", ref.scope());
            outputs.put("location", ref.location());
            outputs.put("clusterName", ref.cluster());
        }
        if (namespace != null) {
            outputs.put("namespace", namespace);
        }
        return outputs;
    }

    private static <T> List<Map<String, Object>> map(List<Map<String, Object>> source,
                                                     Function<Map<String, Object>, Map<String, Object>> fn) {
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            result.add(fn.apply(item));
        }
        return result;
    }

    private static ClusterAdmin.ClusterRef requireCluster(ClusterAdmin.ClusterRef ref) {
        if (ref.cluster() == null || ref.cluster().isBlank()) {
            throw new PluginConfigurationException("Required configuration 'clusterName' is missing or blank");
        }
        if (ref.location() == null || ref.location().isBlank()) {
            throw new PluginConfigurationException("Required configuration 'location' is missing or blank");
        }
        return ref;
    }

    private static int requireReplicas(NodeConfiguration cfg) {
        long replicas = cfg.getLong("replicas", -1);
        if (replicas < 0) {
            throw new PluginConfigurationException("'replicas' must be zero or greater.");
        }
        return (int) replicas;
    }

    /** @return a failure when a destructive operation has not been confirmed, or null when it may proceed */
    private static NodeExecutionResult checkConfirmation(KubernetesOperation operation, NodeConfiguration cfg) {
        if (!operation.destructive()) {
            return null;
        }
        if (!cfg.getBoolean("requireConfirmation", true) || cfg.getBoolean("confirmed", false)) {
            return null;
        }
        return NodeExecutionResult.failure("K8S_CONFIRMATION_REQUIRED",
                operation.displayName() + " is destructive (" + operation.risk() + ") and has not been confirmed. "
                        + "Set 'confirmed' to true from an approval or human-task node, or turn off "
                        + "'requireConfirmation' if the workflow gates it another way.");
    }

    /** Writes a metadata-only audit record. Never a credential, never a manifest body. Best-effort. */
    private void audit(NodeExecutionContext ctx, KubernetesOperation operation, NodeConfiguration cfg,
                       String outcome) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("provider", GkeKubernetesProvider.ID);
            record.put("operation", operation.name());
            record.put("capability", operation.capability());
            record.put("riskLevel", operation.risk().name());
            record.put("destructive", operation.destructive());
            record.put("workflowId", ctx.workflowId());
            record.put("workflowExecutionId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("projectId", cfg.getString("projectId", null));
            record.put("location", cfg.getString("location", null));
            record.put("clusterName", cfg.getString("clusterName", null));
            record.put("namespace", cfg.getString("namespace", null));
            record.put("outcome", outcome);
            record.put("timestamp", Instant.now().toString());
            context.dataStore().put("audit", ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt(), record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write Kubernetes audit record: {}", ex.getMessage());
        }
    }
}
