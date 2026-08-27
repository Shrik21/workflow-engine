package com.orchpilot.workflow.plugins.gcp.kubernetes;

import com.orchpilot.workflow.plugins.gcp.kubernetes.model.KubernetesOperation;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * Builds each node's configuration schema.
 *
 * <h2>Why every operation gets its own schema</h2>
 *
 * The designer renders a node's form from the schema it declares, so a per-operation schema is what makes the form
 * change with the operation — a Scale Deployment node shows a replica count and nothing else, while Apply Manifest
 * shows a manifest box. One shared "Kubernetes Operation" node with every field on it would show forty inputs, most
 * irrelevant, and could not carry per-operation risk. This costs no hand-written UI: it is all declaration.
 *
 * <h2>The credential is always a reference</h2>
 *
 * Every schema starts with a {@code secretRef}, never a password or key field. What the workflow stores is the
 * <em>name</em> of a secret; the value is resolved at execution time through the engine's audited secret provider
 * and never enters the workflow document, its variables, or its output.
 */
final class NodeSchemas {

    private NodeSchemas() {
    }

    static Map<String, Object> forOperation(KubernetesOperation operation) {
        SchemaBuilder schema = SchemaBuilder.object()
                .secretRef("credentialsSecret", "GCP credentials secret name", true)
                .withDescription("credentialsSecret",
                        "The NAME of a secret holding the service-account JSON key (prefix gke.). Never the key "
                                + "itself.")
                .string("projectId", "Project ID", false)
                .withDescription("projectId", "Defaults to the project named in the service-account key.");

        if (operation.layer() == KubernetesOperation.Layer.GKE) {
            gkeFields(schema, operation);
        } else {
            kubernetesFields(schema, operation);
        }
        return schema.build();
    }

    // ------------------------------------------------------------------ GKE control plane

    private static void gkeFields(SchemaBuilder schema, KubernetesOperation operation) {
        boolean listing = operation == KubernetesOperation.CLUSTER_LIST;
        schema.string("location", "Location (region or zone)", !listing)
                .withDescription("location", listing
                        ? "Leave blank to list clusters in every location."
                        : "The cluster's region (e.g. us-central1) or zone (e.g. us-central1-a).");
        if (listing) {
            return;
        }
        schema.string("clusterName", "Cluster name", true);

        switch (operation) {
            case CLUSTER_CREATE -> schema
                    .integer("initialNodeCount", "Initial node count", false)
                    .withDefault("initialNodeCount", 3)
                    .string("machineType", "Machine type", false).withDefault("machineType", "e2-medium")
                    .integer("diskSizeGb", "Node disk size (GB)", false).withDefault("diskSizeGb", 100)
                    .string("network", "Network", false).withDefault("network", "default")
                    .string("subnetwork", "Subnetwork", false)
                    .string("releaseChannel", "Release channel", false)
                    .withDescription("releaseChannel", "RAPID, REGULAR or STABLE. Blank leaves GKE's default.")
                    .bool("enableAutopilot", "Autopilot cluster", false)
                    .withDescription("enableAutopilot",
                            "Autopilot manages nodes for you; node-pool settings above are then ignored.");
            case CLUSTER_DELETE -> confirmation(schema,
                    "Deleting a cluster destroys every workload running on it and cannot be undone.");
            case NODEPOOL_SCALE -> schema
                    .string("nodePoolName", "Node pool name", true)
                    .integer("nodeCount", "Node count", true)
                    .withDescription("nodeCount", "The new number of nodes in the pool.");
            case NODEPOOL_DELETE -> {
                schema.string("nodePoolName", "Node pool name", true);
                confirmation(schema, "Deleting a node pool drains and destroys its nodes.");
            }
            default -> {
                // list/get/health need nothing beyond the cluster reference.
            }
        }
    }

    // ------------------------------------------------------------------ Kubernetes workloads

    private static void kubernetesFields(SchemaBuilder schema, KubernetesOperation operation) {
        schema.string("location", "Cluster location", true)
                .string("clusterName", "Cluster name", true)
                // The label no longer has to say "(advanced)" — the form now says it structurally.
                .string("apiServerUrl", "API server URL", false)
                .withDescription("apiServerUrl",
                        "Optional. Overrides endpoint discovery — only for a cluster fronted by a gateway with a "
                                + "publicly-trusted certificate. Must be https://.")
                .advanced("apiServerUrl");

        if (operation != KubernetesOperation.NAMESPACE_LIST) {
            boolean namespaceRequired = operation == KubernetesOperation.NAMESPACE_CREATE
                    || operation == KubernetesOperation.NAMESPACE_DELETE;
            schema.string("namespace", "Namespace", namespaceRequired);
            if (!namespaceRequired) {
                schema.withDefault("namespace", "default");
            }
        }

        switch (operation) {
            case NAMESPACE_CREATE -> schema.text("labels", "Labels (KEY=value per line)", false);
            case NAMESPACE_DELETE -> confirmation(schema,
                    "Deleting a namespace deletes every resource inside it.");

            case POD_LIST -> selectors(schema);
            case POD_GET -> schema.string("podName", "Pod name", true);
            case POD_LOGS -> schema.string("podName", "Pod name", true)
                    .string("container", "Container", false)
                    .withDescription("container", "Blank uses the pod's first container.")
                    .integer("tailLines", "Tail lines", false).withDefault("tailLines", 200)
                    .withDescription("tailLines", "Capped at 2000 so the result fits in a workflow variable.")
                    .bool("previous", "Previous container instance", false)
                    .withDescription("previous", "Reads the crashed container's logs — use after a CrashLoopBackOff.")
                    .bool("timestamps", "Include timestamps", false);
            case POD_DELETE -> {
                schema.string("podName", "Pod name", true);
                confirmation(schema, "The pod's controller will usually replace it.");
            }
            case POD_EXEC -> schema.string("podName", "Pod name", true)
                    .text("command", "Command", false)
                    .withDescription("command",
                            "Not executed. This node exists so the capability is visible and explicitly refused "
                                    + "rather than silently absent; see the plugin README.");

            case DEPLOYMENT_LIST, STATEFULSET_LIST, DAEMONSET_LIST, JOB_LIST, CRONJOB_LIST, SERVICE_LIST,
                 CONFIGMAP_LIST, SECRET_LIST, INGRESS_LIST, HPA_LIST -> selectors(schema);

            case EVENT_LIST -> schema.integer("limit", "Maximum events", false).withDefault("limit", 100)
                    .string("fieldSelector", "Field selector", false)
                    .withDescription("fieldSelector",
                            "e.g. involvedObject.name=my-pod, or type=Warning for failures only.");

            case DEPLOYMENT_GET, DEPLOYMENT_HEALTH -> schema.string("deploymentName", "Deployment name", true);

            case DEPLOYMENT_CREATE -> schema
                    .string("deploymentName", "Deployment name", true)
                    .string("image", "Container image", true)
                    .integer("replicas", "Replicas", false).withDefault("replicas", 1)
                    .integer("containerPort", "Container port", false)
                    .select("imagePullPolicy", "Image pull policy",
                            List.of("IfNotPresent", "Always", "Never"), false)
                    .text("env", "Environment (KEY=value per line)", false)
                    .withDescription("env",
                            "Literal values only. Never put a credential here — reference a Kubernetes Secret "
                                    + "from a manifest instead.")
                    .string("cpuRequest", "CPU request", false)
                    .string("memoryRequest", "Memory request", false)
                    .string("cpuLimit", "CPU limit", false)
                    .string("memoryLimit", "Memory limit", false)
                    .string("serviceAccountName", "Pod service account", false)
                    .bool("waitForRollout", "Wait for rollout", false).withDefault("waitForRollout", true);

            case DEPLOYMENT_UPDATE_IMAGE -> schema
                    .string("deploymentName", "Deployment name", true)
                    .string("image", "New image", true)
                    .string("container", "Container", false)
                    .withDescription("container", "Blank patches the container named after the deployment.")
                    .bool("waitForRollout", "Wait for rollout", false).withDefault("waitForRollout", true);

            case DEPLOYMENT_SCALE -> schema
                    .string("deploymentName", "Deployment name", true)
                    .integer("replicas", "Replicas", true);

            case DEPLOYMENT_RESTART -> schema
                    .string("deploymentName", "Deployment name", true)
                    .bool("waitForRollout", "Wait for rollout", false).withDefault("waitForRollout", true);

            case DEPLOYMENT_ROLLBACK -> schema
                    .string("deploymentName", "Deployment name", true)
                    .bool("waitForRollout", "Wait for rollout", false).withDefault("waitForRollout", true);

            case DEPLOYMENT_DELETE -> {
                schema.string("deploymentName", "Deployment name", true);
                confirmation(schema, "Deleting a deployment removes it and all of its pods.");
            }

            case STATEFULSET_SCALE -> schema
                    .string("statefulSetName", "StatefulSet name", true)
                    .integer("replicas", "Replicas", true);

            case SERVICE_GET -> schema.string("serviceName", "Service name", true);
            case SERVICE_DELETE -> {
                schema.string("serviceName", "Service name", true);
                confirmation(schema, "Deleting a service removes its address and load balancer.");
            }

            case CONFIGMAP_GET -> schema.string("configMapName", "ConfigMap name", true);
            case CONFIGMAP_DELETE -> {
                schema.string("configMapName", "ConfigMap name", true);
                confirmation(schema, "Workloads mounting this ConfigMap will be affected.");
            }

            case SECRET_DELETE -> {
                schema.string("secretName", "Secret name", true);
                confirmation(schema, "Workloads using this secret will fail once it is gone.");
            }

            case JOB_DELETE -> {
                schema.string("jobName", "Job name", true);
                confirmation(schema, "Deleting a Job removes its pods and its result.");
            }

            case MANIFEST_VALIDATE -> schema
                    .text("manifest", "Manifest (YAML or JSON)", true)
                    .bool("serverDryRun", "Server-side dry run", false)
                    .withDescription("serverDryRun",
                            "Also asks the cluster to validate it without persisting anything. Needs cluster "
                                    + "access; structural validation alone does not.");

            case MANIFEST_APPLY -> schema
                    .text("manifest", "Manifest (YAML or JSON)", true)
                    .bool("dryRun", "Dry run only", false)
                    .withDescription("dryRun", "Sends the manifest for validation but changes nothing.");

            case MANIFEST_DELETE -> {
                schema.text("manifest", "Manifest (YAML or JSON)", true);
                confirmation(schema, "Deletes the resources this manifest describes.");
            }

            default -> {
                // Remaining list operations are covered by the selector block above.
            }
        }

        if (waitsForRollout(operation)) {
            schema.integer("rolloutTimeoutSeconds", "Rollout timeout (seconds)", false)
                    .withDefault("rolloutTimeoutSeconds", 300)
                    .integer("pollIntervalSeconds", "Polling interval (seconds)", false)
                    .withDefault("pollIntervalSeconds", 5)
                    // Correct as they stand for almost every rollout; only a slow cluster needs them changed.
                    .advanced("rolloutTimeoutSeconds", "pollIntervalSeconds");
        }
    }

    private static boolean waitsForRollout(KubernetesOperation operation) {
        return operation == KubernetesOperation.DEPLOYMENT_CREATE
                || operation == KubernetesOperation.DEPLOYMENT_UPDATE_IMAGE
                || operation == KubernetesOperation.DEPLOYMENT_RESTART
                || operation == KubernetesOperation.DEPLOYMENT_ROLLBACK;
    }

    private static void selectors(SchemaBuilder schema) {
        schema.string("labelSelector", "Label selector", false)
                .withDescription("labelSelector", "e.g. app=web,tier=frontend")
                .integer("limit", "Maximum results", false).withDefault("limit", 200);
    }

    /**
     * The second gate on a destructive operation.
     *
     * <p>The node's {@code destructive} flag already makes a supervised AI Agent seek approval. This adds a gate
     * that applies to <em>any</em> caller, agent or not, so a hand-built workflow cannot delete a namespace because
     * a variable resolved to something unexpected. Two independent gates, because either one alone has a bypass.
     */
    private static void confirmation(SchemaBuilder schema, String why) {
        schema.bool("requireConfirmation", "Require confirmation", false)
                .withDefault("requireConfirmation", true)
                .bool("confirmed", "Confirmed", false)
                .withDescription("confirmed", why
                        + " Must be true to proceed while confirmation is required — set it from an approval or "
                        + "human-task node upstream.");
    }
}
