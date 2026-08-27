package com.orchpilot.workflow.plugins.gcp.kubernetes;

import com.orchpilot.workflow.plugins.gcp.kubernetes.model.KubernetesOperation;
import com.orchpilot.workflow.sdk.json.Json;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code META-INF/workflow-plugin.json} honest against {@link KubernetesOperation}.
 *
 * <h2>Why this is worth a test</h2>
 *
 * The manifest is what the Plugin Registry publishes and what the AI Agent's tool catalogue is built from, while
 * the enum is what actually executes. Nothing at compile time ties them together, so a new operation can silently
 * ship without a manifest entry — discoverable only as "the agent can't see the tool" — or, worse, a manifest can
 * advertise a delete as non-destructive and quietly skip the approval gate. This asserts they agree on every node
 * type, capability and risk flag.
 */
class ManifestConsistencyTest {

    @Test
    @SuppressWarnings("unchecked")
    void manifestMatchesTheOperationCatalogue() {
        Map<String, Object> manifest = readManifest();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object entry : (List<Object>) manifest.get("nodes")) {
            nodes.add((Map<String, Object>) entry);
        }

        Map<String, Map<String, Object>> byNodeType = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            byNodeType.put(String.valueOf(node.get("nodeType")), node);
        }

        Set<String> expected = new LinkedHashSet<>();
        for (KubernetesOperation operation : KubernetesOperation.values()) {
            expected.add(operation.nodeType());
        }

        assertThat(byNodeType.keySet())
                .as("every operation is published, and nothing extra is")
                .containsExactlyInAnyOrderElementsOf(expected);

        for (KubernetesOperation operation : KubernetesOperation.values()) {
            Map<String, Object> node = byNodeType.get(operation.nodeType());
            assertThat(node.get("displayName")).isEqualTo(operation.displayName());
            assertThat(node.get("capability")).isEqualTo(operation.capability());
            assertThat(node.get("riskLevel")).isEqualTo(operation.risk().name());
            // The one that would actually bite: a delete published as non-destructive skips the approval gate.
            assertThat(node.get("destructive"))
                    .as("destructive flag for %s", operation.nodeType())
                    .isEqualTo(operation.destructive());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCapabilityIsListedOnce() {
        List<Object> declared = (List<Object>) readManifest().get("capabilities");

        Set<String> fromEnum = new LinkedHashSet<>();
        for (KubernetesOperation operation : KubernetesOperation.values()) {
            // Exec is deliberately in notSupported rather than capabilities.
            if (operation != KubernetesOperation.POD_EXEC) {
                fromEnum.add(operation.capability());
            }
        }

        assertThat(declared).doesNotHaveDuplicates();
        assertThat(declared).containsExactlyInAnyOrderElementsOf(fromEnum);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execIsPublishedAsUnsupportedRatherThanAsAWorkingTool() {
        Map<String, Object> manifest = readManifest();
        Map<String, Object> notSupported = (Map<String, Object>) manifest.get("notSupported");

        assertThat(notSupported).containsKey("kubernetes.pod.exec");
        assertThat(notSupported).containsKey("kubernetes.secret.read");

        Map<String, Object> execNode = null;
        for (Object entry : (List<Object>) manifest.get("nodes")) {
            Map<String, Object> node = (Map<String, Object>) entry;
            if ("K8S_POD_EXEC".equals(node.get("nodeType"))) {
                execNode = node;
            }
        }
        assertThat(execNode).isNotNull();
        // Not offered to the agent as a tool, since it can never succeed.
        assertThat(execNode.get("supportsAI")).isEqualTo(false);
        assertThat(execNode.get("available")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void permissionsCoverExactlyTheHostsTheCodeCalls() {
        Map<String, Object> permissions = (Map<String, Object>) readManifest().get("permissions");
        List<Object> hosts = (List<Object>) permissions.get("network");

        assertThat(hosts).containsExactlyInAnyOrder(
                "container.googleapis.com",   // GKE management
                "oauth2.googleapis.com",      // token exchange
                "*.gke.goog");                // DNS-based cluster control plane
        assertThat((List<Object>) permissions.get("secrets")).containsExactly("gke.");
    }

    private static Map<String, Object> readManifest() {
        // Read from target/classes so the assertions run against the filtered, property-substituted resource.
        try (InputStream stream = ManifestConsistencyTest.class
                .getResourceAsStream("/META-INF/workflow-plugin.json")) {
            assertThat(stream).as("the plugin manifest is on the classpath").isNotNull();
            return Json.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new AssertionError("Could not read the plugin manifest", ex);
        }
    }
}
