package com.orchpilot.workflow.plugins.gcp.kubernetes.manifest;

import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the request bodies for the workload operations that do not take a hand-written manifest.
 *
 * <h2>Why patches rather than read-modify-write</h2>
 *
 * Scaling, image updates and restarts are all expressed as strategic-merge patches. The alternative — GET the
 * deployment, change a field, PUT it back — races with anything else touching the cluster and can silently revert a
 * concurrent change. A patch sends only the intent, so the API server merges it against whatever the current state
 * is. It also means a scale request is a few dozen bytes rather than a whole deployment spec.
 */
public final class Workloads {

    /** The annotation {@code kubectl rollout restart} sets; changing it is what forces a new ReplicaSet. */
    public static final String RESTART_ANNOTATION = "kubectl.kubernetes.io/restartedAt";

    private Workloads() {
    }

    /** A minimal Namespace object. */
    public static Map<String, Object> namespace(String name, Map<String, String> labels) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        if (labels != null && !labels.isEmpty()) {
            metadata.put("labels", new LinkedHashMap<String, Object>(labels));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "v1");
        body.put("kind", "Namespace");
        body.put("metadata", metadata);
        return body;
    }

    /**
     * A Deployment built from the handful of fields a workflow actually sets.
     *
     * <p>This is intentionally the 90% case, not a full Deployment spec: anything richer belongs in a manifest,
     * applied through the Apply Manifest node. Trying to expose every Deployment field as a node input would produce
     * a form nobody can fill in and still not cover what a real chart needs.
     */
    public static Map<String, Object> deployment(NodeConfiguration cfg, String name, String namespace) {
        String image = cfg.requireString("image");
        int replicas = (int) cfg.getLong("replicas", 1);
        Integer containerPort = cfg.getLong("containerPort", 0) > 0
                ? (int) cfg.getLong("containerPort", 0) : null;

        Map<String, String> selectorLabels = new LinkedHashMap<>();
        selectorLabels.put("app", name);

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", name);
        container.put("image", image);
        if (containerPort != null) {
            Map<String, Object> port = new LinkedHashMap<>();
            port.put("containerPort", containerPort);
            container.put("ports", List.of(port));
        }
        List<Map<String, Object>> env = envVars(cfg.getString("env", null));
        if (!env.isEmpty()) {
            container.put("env", env);
        }
        Map<String, Object> resources = resources(cfg);
        if (!resources.isEmpty()) {
            container.put("resources", resources);
        }
        String imagePullPolicy = cfg.getString("imagePullPolicy", null);
        if (imagePullPolicy != null && !imagePullPolicy.isBlank()) {
            container.put("imagePullPolicy", imagePullPolicy);
        }

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("containers", List.of(container));
        String serviceAccount = cfg.getString("serviceAccountName", null);
        if (serviceAccount != null && !serviceAccount.isBlank()) {
            podSpec.put("serviceAccountName", serviceAccount);
        }

        Map<String, Object> podMetadata = new LinkedHashMap<>();
        podMetadata.put("labels", new LinkedHashMap<String, Object>(selectorLabels));

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("metadata", podMetadata);
        template.put("spec", podSpec);

        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("matchLabels", new LinkedHashMap<String, Object>(selectorLabels));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("replicas", replicas);
        spec.put("selector", selector);
        spec.put("template", template);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        metadata.put("labels", new LinkedHashMap<String, Object>(selectorLabels));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "apps/v1");
        body.put("kind", "Deployment");
        body.put("metadata", metadata);
        body.put("spec", spec);
        return body;
    }

    /** {@code spec.replicas = n} — the whole of a scale request. */
    public static Map<String, Object> scalePatch(int replicas) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("replicas", replicas);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spec", spec);
        return patch;
    }

    /**
     * Patches one container's image.
     *
     * <p>The container is addressed by name because a strategic merge merges {@code containers} by the {@code name}
     * key — which is exactly why a sidecar is left alone instead of being wiped out.
     */
    public static Map<String, Object> imagePatch(String containerName, String image) {
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", containerName);
        container.put("image", image);

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("containers", List.of(container));

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("spec", podSpec);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("template", template);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spec", spec);
        return patch;
    }

    /**
     * The rolling-restart patch.
     *
     * <p>Kubernetes has no "restart" verb. Changing an annotation on the pod template changes the template hash,
     * which makes the Deployment controller roll out a new ReplicaSet — which is precisely what
     * {@code kubectl rollout restart} does, and why the timestamp has to be new each time.
     */
    public static Map<String, Object> restartPatch(Instant at) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put(RESTART_ANNOTATION, at.toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("annotations", annotations);

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("metadata", metadata);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("template", template);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spec", spec);
        return patch;
    }

    /** Replaces a Deployment's pod template with an earlier one — the mechanism behind a rollback. */
    public static Map<String, Object> templatePatch(Map<String, Object> podTemplate) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("template", podTemplate);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spec", spec);
        return patch;
    }

    /** A ConfigMap from {@code key=value} lines. */
    public static Map<String, Object> configMap(String name, String namespace, Map<String, String> data) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "v1");
        body.put("kind", "ConfigMap");
        body.put("metadata", metadata);
        body.put("data", new LinkedHashMap<String, Object>(data));
        return body;
    }

    /**
     * Parses {@code KEY=value} lines into container env entries.
     *
     * <p>Only the literal form is supported — no {@code valueFrom}, so a node cannot be used to pull a Kubernetes
     * Secret's contents into a pod definition that then surfaces in workflow output.
     */
    public static List<Map<String, Object>> envVars(String lines) {
        List<Map<String, Object>> env = new ArrayList<>();
        if (lines == null || lines.isBlank()) {
            return env;
        }
        for (String line : lines.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", trimmed.substring(0, equals).trim());
            entry.put("value", trimmed.substring(equals + 1).trim());
            env.add(entry);
        }
        return env;
    }

    /** Parses {@code KEY=value} lines into a map, used for ConfigMap data and labels. */
    public static Map<String, String> keyValues(String lines) {
        Map<String, String> values = new LinkedHashMap<>();
        if (lines == null || lines.isBlank()) {
            return values;
        }
        for (String line : lines.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
        }
        return values;
    }

    private static Map<String, Object> resources(NodeConfiguration cfg) {
        Map<String, Object> requests = quantities(cfg.getString("cpuRequest", null),
                cfg.getString("memoryRequest", null));
        Map<String, Object> limits = quantities(cfg.getString("cpuLimit", null),
                cfg.getString("memoryLimit", null));
        Map<String, Object> resources = new LinkedHashMap<>();
        if (!requests.isEmpty()) {
            resources.put("requests", requests);
        }
        if (!limits.isEmpty()) {
            resources.put("limits", limits);
        }
        return resources;
    }

    private static Map<String, Object> quantities(String cpu, String memory) {
        Map<String, Object> quantities = new LinkedHashMap<>();
        if (cpu != null && !cpu.isBlank()) {
            quantities.put("cpu", cpu.trim());
        }
        if (memory != null && !memory.isBlank()) {
            quantities.put("memory", memory.trim());
        }
        return quantities;
    }
}
