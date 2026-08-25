package com.orchpilot.workflow.plugins.gcp.kubernetes.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens Kubernetes and GKE objects into the shallow maps workflow nodes actually branch on.
 *
 * <h2>Why flatten at all</h2>
 *
 * A Pod as the API returns it is several kilobytes nested six levels deep. A Decision node asking "is it ready?"
 * should not have to write {@code status.containerStatuses[0].ready}, and an AI Agent should not have to spend
 * thousands of tokens of context rediscovering the schema on every call. Each summary below is the set of fields
 * that answer the questions workflows actually ask — the full object is still available on the single-resource
 * Get nodes for anything else.
 *
 * <h2>The Secret rule</h2>
 *
 * {@link #secret} exists to make the safe behaviour the only behaviour: it returns a Secret's name, type and
 * <em>key names</em>, and never the {@code data} map. There is no flag to turn that off. A Kubernetes Secret's
 * values are credentials, and this plugin's whole contract is that credentials do not reach workflow variables,
 * logs, or the model.
 */
public final class Summaries {

    private Summaries() {
    }

    // ------------------------------------------------------------------ Kubernetes

    public static Map<String, Object> pod(Map<String, Object> pod) {
        Map<String, Object> metadata = child(pod, "metadata");
        Map<String, Object> status = child(pod, "status");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(metadata, "name"));
        summary.put("namespace", string(metadata, "namespace"));
        summary.put("phase", string(status, "phase"));
        summary.put("podIP", string(status, "podIP"));
        summary.put("nodeName", string(child(pod, "spec"), "nodeName"));
        summary.put("startTime", string(status, "startTime"));

        // Roll the per-container statuses up into the three things a workflow asks about a pod.
        int restarts = 0;
        boolean allReady = true;
        String waitingReason = null;
        List<Map<String, Object>> containers = children(status, "containerStatuses");
        for (Map<String, Object> container : containers) {
            Object restartCount = container.get("restartCount");
            if (restartCount instanceof Number n) {
                restarts += n.intValue();
            }
            if (!Boolean.TRUE.equals(container.get("ready"))) {
                allReady = false;
            }
            Map<String, Object> waiting = child(child(container, "state"), "waiting");
            if (waitingReason == null && waiting != null) {
                waitingReason = string(waiting, "reason");
            }
        }
        summary.put("containerCount", containers.size());
        summary.put("restarts", restarts);
        summary.put("ready", !containers.isEmpty() && allReady);
        // The reason a pod is stuck (ImagePullBackOff, CrashLoopBackOff) is the single most useful diagnostic field.
        summary.put("reason", waitingReason != null ? waitingReason : string(status, "reason"));
        return summary;
    }

    public static Map<String, Object> deployment(Map<String, Object> deployment) {
        Map<String, Object> metadata = child(deployment, "metadata");
        Map<String, Object> spec = child(deployment, "spec");
        Map<String, Object> status = child(deployment, "status");

        int desired = integer(spec, "replicas", 0);
        int ready = integer(status, "readyReplicas", 0);
        int available = integer(status, "availableReplicas", 0);
        int updated = integer(status, "updatedReplicas", 0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(metadata, "name"));
        summary.put("namespace", string(metadata, "namespace"));
        summary.put("desiredReplicas", desired);
        summary.put("readyReplicas", ready);
        summary.put("availableReplicas", available);
        summary.put("updatedReplicas", updated);
        // "Healthy" means the rollout finished AND every replica is serving — either alone is misleading mid-deploy.
        summary.put("healthy", desired > 0 && ready == desired && updated == desired && available == desired);
        summary.put("images", images(deployment));
        return summary;
    }

    public static Map<String, Object> service(Map<String, Object> service) {
        Map<String, Object> metadata = child(service, "metadata");
        Map<String, Object> spec = child(service, "spec");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(metadata, "name"));
        summary.put("namespace", string(metadata, "namespace"));
        summary.put("type", string(spec, "type"));
        summary.put("clusterIP", string(spec, "clusterIP"));
        summary.put("externalAddress", externalAddress(service));
        List<Object> ports = new ArrayList<>();
        for (Map<String, Object> port : children(spec, "ports")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("port", port.get("port"));
            entry.put("targetPort", port.get("targetPort"));
            entry.put("nodePort", port.get("nodePort"));
            entry.put("protocol", port.get("protocol"));
            ports.add(entry);
        }
        summary.put("ports", ports);
        return summary;
    }

    /** Name, type and key names only — never the values. See the class note. */
    public static Map<String, Object> secret(Map<String, Object> secret) {
        Map<String, Object> metadata = child(secret, "metadata");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(metadata, "name"));
        summary.put("namespace", string(metadata, "namespace"));
        summary.put("type", string(secret, "type"));
        Map<String, Object> data = child(secret, "data");
        summary.put("keys", data == null ? List.of() : new ArrayList<>(data.keySet()));
        summary.put("keyCount", data == null ? 0 : data.size());
        return summary;
    }

    public static Map<String, Object> namespaceSummary(Map<String, Object> namespace) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(child(namespace, "metadata"), "name"));
        summary.put("status", string(child(namespace, "status"), "phase"));
        summary.put("createdAt", string(child(namespace, "metadata"), "creationTimestamp"));
        return summary;
    }

    /** Replica-count summary shared by StatefulSets, DaemonSets and ReplicaSets. */
    public static Map<String, Object> replicaWorkload(Map<String, Object> workload) {
        Map<String, Object> metadata = child(workload, "metadata");
        Map<String, Object> spec = child(workload, "spec");
        Map<String, Object> status = child(workload, "status");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(metadata, "name"));
        summary.put("namespace", string(metadata, "namespace"));
        summary.put("kind", string(workload, "kind"));
        // A DaemonSet has no spec.replicas — its desired count is driven by the node count instead.
        summary.put("desiredReplicas", spec != null && spec.containsKey("replicas")
                ? integer(spec, "replicas", 0) : integer(status, "desiredNumberScheduled", 0));
        summary.put("readyReplicas", status != null && status.containsKey("readyReplicas")
                ? integer(status, "readyReplicas", 0) : integer(status, "numberReady", 0));
        summary.put("images", images(workload));
        return summary;
    }

    public static Map<String, Object> job(Map<String, Object> job) {
        Map<String, Object> status = child(job, "status");
        int succeeded = integer(status, "succeeded", 0);
        int failed = integer(status, "failed", 0);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(child(job, "metadata"), "name"));
        summary.put("namespace", string(child(job, "metadata"), "namespace"));
        summary.put("active", integer(status, "active", 0));
        summary.put("succeeded", succeeded);
        summary.put("failed", failed);
        summary.put("completed", succeeded > 0 && integer(status, "active", 0) == 0);
        summary.put("startTime", string(status, "startTime"));
        summary.put("completionTime", string(status, "completionTime"));
        return summary;
    }

    public static Map<String, Object> cronJob(Map<String, Object> cronJob) {
        Map<String, Object> spec = child(cronJob, "spec");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(child(cronJob, "metadata"), "name"));
        summary.put("namespace", string(child(cronJob, "metadata"), "namespace"));
        summary.put("schedule", string(spec, "schedule"));
        summary.put("suspended", Boolean.TRUE.equals(spec == null ? null : spec.get("suspend")));
        summary.put("lastScheduleTime", string(child(cronJob, "status"), "lastScheduleTime"));
        return summary;
    }

    public static Map<String, Object> ingress(Map<String, Object> ingress) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(child(ingress, "metadata"), "name"));
        summary.put("namespace", string(child(ingress, "metadata"), "namespace"));
        summary.put("address", externalAddress(ingress));
        List<Object> hosts = new ArrayList<>();
        for (Map<String, Object> rule : children(child(ingress, "spec"), "rules")) {
            Object host = rule.get("host");
            if (host != null) {
                hosts.add(host);
            }
        }
        summary.put("hosts", hosts);
        return summary;
    }

    public static Map<String, Object> hpa(Map<String, Object> hpa) {
        Map<String, Object> spec = child(hpa, "spec");
        Map<String, Object> status = child(hpa, "status");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(child(hpa, "metadata"), "name"));
        summary.put("namespace", string(child(hpa, "metadata"), "namespace"));
        summary.put("target", string(child(spec, "scaleTargetRef"), "name"));
        summary.put("minReplicas", integer(spec, "minReplicas", 0));
        summary.put("maxReplicas", integer(spec, "maxReplicas", 0));
        summary.put("currentReplicas", integer(status, "currentReplicas", 0));
        summary.put("desiredReplicas", integer(status, "desiredReplicas", 0));
        return summary;
    }

    public static Map<String, Object> event(Map<String, Object> event) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", string(event, "type"));
        summary.put("reason", string(event, "reason"));
        summary.put("message", string(event, "message"));
        summary.put("count", integer(event, "count", 1));
        summary.put("lastTimestamp", string(event, "lastTimestamp"));
        Map<String, Object> involved = child(event, "involvedObject");
        summary.put("objectKind", string(involved, "kind"));
        summary.put("objectName", string(involved, "name"));
        return summary;
    }

    public static Map<String, Object> configMap(Map<String, Object> configMap, boolean includeData) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(child(configMap, "metadata"), "name"));
        summary.put("namespace", string(child(configMap, "metadata"), "namespace"));
        Map<String, Object> data = child(configMap, "data");
        summary.put("keys", data == null ? List.of() : new ArrayList<>(data.keySet()));
        if (includeData) {
            summary.put("data", data == null ? Map.of() : data);
        }
        return summary;
    }

    /** A generic fallback for kinds without a bespoke summary. */
    public static Map<String, Object> generic(Map<String, Object> resource) {
        Map<String, Object> metadata = child(resource, "metadata");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(metadata, "name"));
        summary.put("namespace", string(metadata, "namespace"));
        summary.put("kind", string(resource, "kind"));
        summary.put("createdAt", string(metadata, "creationTimestamp"));
        return summary;
    }

    // ------------------------------------------------------------------ GKE

    public static Map<String, Object> cluster(Map<String, Object> cluster) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(cluster, "name"));
        summary.put("location", string(cluster, "location"));
        summary.put("status", string(cluster, "status"));
        summary.put("currentMasterVersion", string(cluster, "currentMasterVersion"));
        summary.put("currentNodeVersion", string(cluster, "currentNodeVersion"));
        summary.put("currentNodeCount", integer(cluster, "currentNodeCount", 0));
        summary.put("nodePoolCount", children(cluster, "nodePools").size());
        // The endpoint is reported so an operator can see whether a DNS endpoint exists; see the provider's note.
        Map<String, Object> dns = child(child(cluster, "controlPlaneEndpointsConfig"), "dnsEndpointConfig");
        summary.put("dnsEndpoint", string(dns, "endpoint"));
        summary.put("autopilot", Boolean.TRUE.equals(child(cluster, "autopilot") == null
                ? null : child(cluster, "autopilot").get("enabled")));
        return summary;
    }

    public static Map<String, Object> nodePool(Map<String, Object> nodePool) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", string(nodePool, "name"));
        summary.put("status", string(nodePool, "status"));
        summary.put("version", string(nodePool, "version"));
        summary.put("initialNodeCount", integer(nodePool, "initialNodeCount", 0));
        summary.put("machineType", string(child(nodePool, "config"), "machineType"));
        Map<String, Object> autoscaling = child(nodePool, "autoscaling");
        summary.put("autoscalingEnabled", Boolean.TRUE.equals(autoscaling == null
                ? null : autoscaling.get("enabled")));
        summary.put("minNodeCount", integer(autoscaling, "minNodeCount", 0));
        summary.put("maxNodeCount", integer(autoscaling, "maxNodeCount", 0));
        return summary;
    }

    // ------------------------------------------------------------------ helpers

    /** @return the container images of a workload's pod template, so an update can be verified without a second read */
    public static List<Object> images(Map<String, Object> workload) {
        List<Object> images = new ArrayList<>();
        Map<String, Object> podSpec = child(child(child(workload, "spec"), "template"), "spec");
        for (Map<String, Object> container : children(podSpec, "containers")) {
            Object image = container.get("image");
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    /** @return the LoadBalancer IP or hostname, whichever the cloud assigned, or null while still pending */
    private static String externalAddress(Map<String, Object> resource) {
        for (Map<String, Object> ingress : children(child(child(resource, "status"), "loadBalancer"), "ingress")) {
            Object ip = ingress.get("ip");
            if (ip != null) {
                return String.valueOf(ip);
            }
            Object hostname = ingress.get("hostname");
            if (hostname != null) {
                return String.valueOf(hostname);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> child(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        return parent.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> children(Map<String, Object> parent, String key) {
        if (parent == null || !(parent.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    public static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
