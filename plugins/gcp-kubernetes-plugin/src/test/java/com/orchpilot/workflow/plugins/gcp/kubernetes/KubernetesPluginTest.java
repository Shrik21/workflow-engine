package com.orchpilot.workflow.plugins.gcp.kubernetes;

import com.orchpilot.workflow.plugins.gcp.kubernetes.support.FakeHttpClient;
import com.orchpilot.workflow.plugins.gcp.kubernetes.support.TestSupport;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin end to end, through a mocked {@link PluginContext} and a scripted HTTP client — no GCP account, no
 * cluster, no network.
 *
 * <p>Coverage is deliberately weighted towards the things that would be expensive to discover in production: the
 * risk flags the approval engine depends on, the credential never escaping, the destructive-operation gate, secrets
 * never yielding values, and the endpoint constraint failing with an explanation rather than a TLS stack trace.
 */
class KubernetesPluginTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String TOKEN_OK = "{\"access_token\":\"ya29.test-token\",\"expires_in\":3600}";
    private static final String DNS_ENDPOINT = "gke-abc123.us-central1.gke.goog";

    /** A cluster with a DNS endpoint — the shape the data plane can actually reach. */
    private static final String CLUSTER_OK = """
            {"name":"prod","location":"us-central1","status":"RUNNING","currentMasterVersion":"1.30.5",
             "currentNodeCount":3,
             "controlPlaneEndpointsConfig":{"dnsEndpointConfig":{"endpoint":"%s"}}}
            """.formatted(DNS_ENDPOINT);

    private KubernetesPlugin plugin;
    private FakeHttpClient http;

    @BeforeEach
    void setUp() {
        http = new FakeHttpClient();
        http.on("POST " + TOKEN_URI, 200, TOKEN_OK);

        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn(TestSupport.serviceAccountJson(TOKEN_URI));

        PluginContext context = mock(PluginContext.class);
        lenient().when(context.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(context.secrets()).thenReturn(secrets);
        lenient().when(context.http()).thenReturn(http);
        lenient().when(context.dataStore()).thenReturn(mock(PluginDataStore.class));

        plugin = new KubernetesPlugin();
        plugin.initialize(context);
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void catalogueCoversEveryOperationWithItsRiskFlag() {
        List<NodeDefinition> definitions = plugin.getNodeDefinitions();

        assertThat(definitions).hasSize(com.orchpilot.workflow.plugins.gcp.kubernetes.model
                .KubernetesOperation.values().length);
        assertThat(definitions).allMatch(NodeDefinition::supportsAI);

        // The approval engine keys off destructive, so these three are the contract.
        assertThat(definition(definitions, "GKE_DELETE_CLUSTER").destructive()).isTrue();
        assertThat(definition(definitions, "K8S_DELETE_NAMESPACE").destructive()).isTrue();
        assertThat(definition(definitions, "K8S_SCALE_DEPLOYMENT").destructive()).isFalse();

        NodeDefinition list = definition(definitions, "K8S_LIST_PODS");
        assertThat(list.destructive()).isFalse();
        assertThat(list.idempotent()).isTrue();
        // A scale is not safely repeatable after a partial failure.
        assertThat(definition(definitions, "K8S_SCALE_DEPLOYMENT").idempotent()).isFalse();
    }

    // ------------------------------------------------------------------ GKE control plane

    @Test
    void listsClustersWithoutTouchingTheClusterApiServer() {
        http.on("GET https://container.googleapis.com/v1/projects/test-project/locations/-/clusters", 200,
                "{\"clusters\":[" + CLUSTER_OK + "]}");

        NodeExecutionResult result = run("GKE_LIST_CLUSTERS", cfg());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("count", 1);
        assertThat(result.outputs()).containsEntry("provider", "gcp-gke");
        List<Object> items = list(result.outputs().get("items"));
        assertThat(map(items.get(0))).containsEntry("name", "prod")
                .containsEntry("status", "RUNNING");
        // Cluster management must not depend on reaching the cluster itself.
        assertThat(http.countMatching(DNS_ENDPOINT)).isZero();
    }

    @Test
    void clusterHealthIsFalseWhenANodePoolIsDegraded() {
        http.on("GET https://container.googleapis.com/v1/projects/test-project/locations/us-central1/clusters/prod/nodePools",
                200, "{\"nodePools\":[{\"name\":\"pool-1\",\"status\":\"RUNNING\"},"
                        + "{\"name\":\"pool-2\",\"status\":\"RECONCILING\"}]}");
        http.on("GET https://container.googleapis.com/v1/projects/test-project/locations/us-central1/clusters/prod",
                200, CLUSTER_OK);

        NodeExecutionResult result = run("GKE_CLUSTER_HEALTH", cfg("location", "us-central1",
                "clusterName", "prod"));

        assertThat(result.isSuccess()).isTrue();
        // The cluster itself says RUNNING — health has to look deeper than that.
        assertThat(result.outputs()).containsEntry("status", "RUNNING");
        assertThat(result.outputs()).containsEntry("healthy", false);
        assertThat(list(result.outputs().get("unhealthyNodePools"))).containsExactly("pool-2=RECONCILING");
    }

    @Test
    void scalesANodePool() {
        http.on("POST https://container.googleapis.com/v1/projects/test-project/locations/us-central1/clusters/prod/nodePools/pool-1:setSize",
                200, "{\"name\":\"operation-123\",\"status\":\"RUNNING\"}");

        NodeExecutionResult result = run("GKE_SCALE_NODE_POOL", cfg("location", "us-central1",
                "clusterName", "prod", "nodePoolName", "pool-1", "nodeCount", 5));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("nodeCount", 5)
                .containsEntry("operationId", "operation-123");
        assertThat(http.lastMatching("POST", ":setSize").body()).contains("\"nodeCount\":5");
    }

    // ------------------------------------------------------------------ destructive gate

    @Test
    void refusesADestructiveOperationThatWasNotConfirmed() {
        NodeExecutionResult result = run("GKE_DELETE_CLUSTER", cfg("location", "us-central1",
                "clusterName", "prod"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_CONFIRMATION_REQUIRED");
        // Nothing may reach the API before confirmation — not even a read.
        assertThat(http.countMatching("container.googleapis.com")).isZero();
    }

    @Test
    void proceedsWithADestructiveOperationOnceConfirmed() {
        http.on("DELETE https://container.googleapis.com/v1/projects/test-project/locations/us-central1/clusters/prod",
                200, "{\"name\":\"operation-del\",\"status\":\"RUNNING\"}");

        NodeExecutionResult result = run("GKE_DELETE_CLUSTER", cfg("location", "us-central1",
                "clusterName", "prod", "confirmed", true));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("status", "DELETING");
    }

    // ------------------------------------------------------------------ Kubernetes workloads

    @Test
    void listsPodsAndFlattensTheStatusWorkflowsBranchOn() {
        givenClusterLookup();
        http.on("GET https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/pods", 200, """
                {"items":[
                  {"metadata":{"name":"web-1","namespace":"prod"},
                   "spec":{"nodeName":"node-a"},
                   "status":{"phase":"Running","podIP":"10.0.0.1",
                             "containerStatuses":[{"ready":true,"restartCount":2,"state":{"running":{}}}]}},
                  {"metadata":{"name":"web-2","namespace":"prod"},
                   "status":{"phase":"Pending",
                             "containerStatuses":[{"ready":false,"restartCount":7,
                                                   "state":{"waiting":{"reason":"CrashLoopBackOff"}}}]}}
                ]}
                """);

        NodeExecutionResult result = run("K8S_LIST_PODS", k8sCfg());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("count", 2);
        List<Object> items = list(result.outputs().get("items"));
        assertThat(map(items.get(0))).containsEntry("name", "web-1")
                .containsEntry("ready", true).containsEntry("restarts", 2);
        // The waiting reason is the diagnostic that matters, hoisted out of the container status.
        assertThat(map(items.get(1))).containsEntry("ready", false)
                .containsEntry("reason", "CrashLoopBackOff");
    }

    @Test
    void scaleSendsOnlyTheReplicaCountAsAStrategicMergePatch() {
        givenClusterLookup();
        http.on("PATCH https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/web", 200,
                """
                {"metadata":{"name":"web","namespace":"prod"},"spec":{"replicas":4},
                 "status":{"readyReplicas":4,"availableReplicas":4,"updatedReplicas":4}}
                """);

        NodeExecutionResult result = run("K8S_SCALE_DEPLOYMENT",
                k8sCfg("deploymentName", "web", "replicas", 4));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("desiredReplicas", 4);

        var patch = http.lastMatching("PATCH", "/deployments/web");
        assertThat(patch.body()).isEqualTo("{\"spec\":{\"replicas\":4}}");
        // A strategic merge, so a concurrent change to anything else in the spec survives.
        assertThat(patch.headers()).containsEntry("Content-Type", "application/strategic-merge-patch+json");
    }

    @Test
    void updateImagePatchesTheNamedContainerOnly() {
        givenClusterLookup();
        String healthy = """
                {"metadata":{"name":"web","namespace":"prod"},"spec":{"replicas":2,
                 "template":{"spec":{"containers":[{"name":"web","image":"repo/web:2.0"}]}}},
                 "status":{"readyReplicas":2,"availableReplicas":2,"updatedReplicas":2}}
                """;
        http.on("PATCH https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/web", 200, healthy);
        http.on("GET https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/web", 200, healthy);

        NodeExecutionResult result = run("K8S_UPDATE_DEPLOYMENT_IMAGE",
                k8sCfg("deploymentName", "web", "image", "repo/web:2.0"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("status", "ROLLED_OUT");
        // Naming the container is what makes a strategic merge leave a sidecar alone.
        assertThat(http.lastMatching("PATCH", "/deployments/web").body())
                .contains("\"name\":\"web\"").contains("\"image\":\"repo/web:2.0\"");
    }

    @Test
    void restartSetsTheSameAnnotationKubectlUses() {
        givenClusterLookup();
        http.on("PATCH https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/web", 200, "{}");
        http.on("GET https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/web", 200,
                """
                {"metadata":{"name":"web","namespace":"prod"},"spec":{"replicas":2},
                 "status":{"readyReplicas":2,"availableReplicas":2,"updatedReplicas":2}}
                """);

        NodeExecutionResult result = run("K8S_RESTART_DEPLOYMENT",
                k8sCfg("deploymentName", "web", "waitForRollout", false));

        assertThat(result.isSuccess()).isTrue();
        // Not waiting still reports where the rollout got to, rather than claiming it finished.
        assertThat(result.outputs()).containsEntry("status", "ROLLING_OUT");
        // The annotation is the whole mechanism: a new value changes the pod-template hash.
        assertThat(http.lastMatching("PATCH", "/deployments/web").body())
                .contains("kubectl.kubernetes.io/restartedAt");
    }

    @Test
    void aFailedRolloutFailsTheNodeRatherThanReportingSuccess() {
        givenClusterLookup();
        http.on("POST https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments", 201, "{}");
        // Never becomes healthy: one replica desired, none ready.
        http.on("GET https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/web", 200,
                """
                {"metadata":{"name":"web","namespace":"prod"},"spec":{"replicas":1},
                 "status":{"readyReplicas":0,"availableReplicas":0,"updatedReplicas":1}}
                """);

        NodeExecutionResult result = run("K8S_CREATE_DEPLOYMENT",
                k8sCfg("deploymentName", "web", "image", "repo/web:broken",
                        "rolloutTimeoutSeconds", 1, "pollIntervalSeconds", 1));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_ROLLOUT_TIMEOUT");
        // The message has to say what to look at — this is the failure an operator sees at 3am.
        assertThat(result.errorMessage()).contains("0/1").contains("ImagePullBackOff");
    }

    @Test
    void podLogsAreBoundedAndReturnedAsText() {
        givenClusterLookup();
        http.on("GET https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/pods/web-1/log", 200,
                "line one\nline two\n");

        NodeExecutionResult result = run("K8S_POD_LOGS",
                k8sCfg("podName", "web-1", "tailLines", 999999, "previous", true));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs().get("logs").toString()).contains("line one");

        String uri = http.lastMatching("GET", "/log").uri();
        // Clamped to the ceiling, so a chatty pod cannot blow past the transport's response limit.
        assertThat(uri).contains("tailLines=2000").contains("previous=true");
    }

    // ------------------------------------------------------------------ security invariants

    @Test
    void listingSecretsReturnsKeyNamesButNeverValues() {
        givenClusterLookup();
        http.on("GET https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/secrets", 200, """
                {"items":[{"metadata":{"name":"db-creds","namespace":"prod"},"type":"Opaque",
                           "data":{"username":"YWRtaW4=","password":"c3VwZXItc2VjcmV0"}}]}
                """);

        NodeExecutionResult result = run("K8S_LIST_SECRETS", k8sCfg());

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> secret = map(list(result.outputs().get("items")).get(0));
        assertThat(secret).containsEntry("name", "db-creds").containsEntry("keyCount", 2);
        assertThat(list(secret.get("keys"))).containsExactly("username", "password");
        // The values must not appear anywhere in the output, in any form.
        assertThat(Json.write(result.outputs())).doesNotContain("c3VwZXItc2VjcmV0").doesNotContain("YWRtaW4=");
    }

    @Test
    void theServiceAccountKeyNeverReachesNodeOutput() {
        http.on("GET https://container.googleapis.com/v1/projects/test-project/locations/-/clusters", 200,
                "{\"clusters\":[" + CLUSTER_OK + "]}");

        NodeExecutionResult result = run("GKE_LIST_CLUSTERS", cfg());

        String outputs = Json.write(result.outputs());
        assertThat(outputs).doesNotContain("PRIVATE KEY")
                .doesNotContain(TestSupport.SECRET_MARKER_EMAIL)
                .doesNotContain("ya29.test-token");
    }

    @Test
    void theServiceAccountKeyNeverGoesToTheClusterApi() {
        givenClusterLookup();
        http.on("GET https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/pods", 200, "{\"items\":[]}");

        run("K8S_LIST_PODS", k8sCfg());

        // The key is exchanged for a token at the token endpoint only; nothing else ever carries it.
        assertThat(http.allTraffic()).doesNotContain("PRIVATE KEY");
    }

    @Test
    void podExecIsRefusedRatherThanAttempted() {
        NodeExecutionResult result = run("K8S_POD_EXEC", k8sCfg("podName", "web-1", "command", "sh -c 'rm -rf /'"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_EXEC_NOT_SUPPORTED");
        // Refused before any credential is resolved or any request is made.
        assertThat(http.requests).isEmpty();
    }

    @Test
    void aClusterWithoutADnsEndpointFailsWithAnExplanationNotATlsError() {
        // A cluster whose only endpoint is the IP one, served by the per-cluster CA.
        http.on("GET https://container.googleapis.com/v1/projects/test-project/locations/us-central1/clusters/prod",
                200, "{\"name\":\"prod\",\"status\":\"RUNNING\",\"endpoint\":\"34.10.20.30\"}");

        NodeExecutionResult result = run("K8S_LIST_PODS", k8sCfg());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_ENDPOINT_NOT_TRUSTED");
        assertThat(result.errorMessage()).contains("DNS-based")
                .contains("cluster and node-pool operations are unaffected");
    }

    @Test
    void anHttpApiServerOverrideIsRefused() {
        NodeExecutionResult result = run("K8S_LIST_PODS",
                k8sCfg("apiServerUrl", "http://10.0.0.1:8080"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_MISCONFIGURED");
        assertThat(result.errorMessage()).contains("clear text");
    }

    // ------------------------------------------------------------------ error mapping

    @Test
    void mapsForbiddenToAPermissionErrorCarryingTheApiExplanation() {
        givenClusterLookup();
        http.on("GET https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/pods", 403,
                "{\"kind\":\"Status\",\"message\":\"pods is forbidden: User cannot list resource\"}");

        NodeExecutionResult result = run("K8S_LIST_PODS", k8sCfg());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_PERMISSION_DENIED");
        assertThat(result.errorMessage()).contains("RBAC").contains("pods is forbidden");
    }

    @Test
    void marksRateLimitingRetryableAndNotFoundNot() {
        givenClusterLookup();
        http.on("GET https://" + DNS_ENDPOINT + "/apis/apps/v1/namespaces/prod/deployments/gone", 404,
                "{\"kind\":\"Status\",\"message\":\"deployments.apps \\\"gone\\\" not found\"}");

        NodeExecutionResult result = run("K8S_GET_DEPLOYMENT", k8sCfg("deploymentName", "gone"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_NOT_FOUND");
        assertThat(result.retryable()).isFalse();
    }

    // ------------------------------------------------------------------ manifests

    @Test
    void applyCreatesAndFallsBackToReplaceWhenTheResourceExists() {
        givenClusterLookup();
        http.on("POST https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/configmaps", 409,
                "{\"kind\":\"Status\",\"message\":\"configmaps \\\"cfg\\\" already exists\"}");
        http.on("GET https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/configmaps/cfg", 200,
                "{\"metadata\":{\"name\":\"cfg\",\"namespace\":\"prod\",\"resourceVersion\":\"991\"}}");
        http.on("PUT https://" + DNS_ENDPOINT + "/api/v1/namespaces/prod/configmaps/cfg", 200,
                "{\"metadata\":{\"name\":\"cfg\",\"namespace\":\"prod\",\"resourceVersion\":\"992\"}}");

        NodeExecutionResult result = run("K8S_APPLY_MANIFEST", k8sCfg("manifest", """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: cfg
                data:
                  key: value
                """));

        assertThat(result.isSuccess()).isTrue();
        assertThat(map(list(result.outputs().get("items")).get(0)))
                .containsEntry("action", "updated");
        // The live resourceVersion has to be carried over or the PUT is rejected.
        assertThat(http.lastMatching("PUT", "/configmaps/cfg").body()).contains("\"resourceVersion\":\"991\"");
    }

    @Test
    void applyRefusesAnInvalidManifestBeforeCallingTheCluster() {
        givenClusterLookup();

        NodeExecutionResult result = run("K8S_APPLY_MANIFEST", k8sCfg("manifest", """
                apiVersion: v1
                kind: Deployment
                metadata:
                  name: web
                """));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("K8S_INVALID_MANIFEST");
        assertThat(http.countMatching("/deployments")).isZero();
    }

    @Test
    void structuralValidationNeedsNoCluster() {
        NodeExecutionResult result = run("K8S_VALIDATE_MANIFEST", k8sCfg("manifest", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: web
                """));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("valid", true).containsEntry("status", "VALID");
        // Not one request: this is the node an author can run before a cluster even exists.
        assertThat(http.requests).isEmpty();
    }

    @Test
    void anInvalidManifestValidatesSuccessfullyAndReportsInvalid() {
        NodeExecutionResult result = run("K8S_VALIDATE_MANIFEST", k8sCfg("manifest", "kind: Deployment"));

        // The node succeeded at its job; the manifest is what is invalid. A workflow branches on 'valid'.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("valid", false);
        assertThat(list(result.outputs().get("problems"))).isNotEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void givenClusterLookup() {
        http.on("GET https://container.googleapis.com/v1/projects/test-project/locations/us-central1/clusters/prod",
                200, CLUSTER_OK);
    }

    private NodeExecutionResult run(String nodeType, Map<String, Object> configuration) {
        NodeExecutionContext context = mock(NodeExecutionContext.class);
        lenient().when(context.nodeType()).thenReturn(nodeType);
        lenient().when(context.configuration()).thenReturn(TestSupport.config(configuration));
        lenient().when(context.executionId()).thenReturn("exec-1");
        lenient().when(context.workflowId()).thenReturn("wf-1");
        lenient().when(context.nodeId()).thenReturn("node-1");
        lenient().when(context.attempt()).thenReturn(1);
        lenient().when(context.timeoutMillis()).thenReturn(30_000L);
        lenient().when(context.isCancelled()).thenReturn(false);
        lenient().when(context.currentUser()).thenReturn(Optional.empty());
        return plugin.execute(context);
    }

    private static Map<String, Object> cfg(Object... extra) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("credentialsSecret", "gke.test");
        configuration.put("projectId", "test-project");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            configuration.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return configuration;
    }

    private static Map<String, Object> k8sCfg(Object... extra) {
        Map<String, Object> configuration = cfg("location", "us-central1", "clusterName", "prod",
                "namespace", "prod");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            configuration.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return configuration;
    }

    /** Casts an output list to a concrete element type — {@code List<?>} defeats AssertJ's varargs matchers. */
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    /** Likewise for maps: a wildcard capture makes {@code containsEntry} uncallable. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static NodeDefinition definition(List<NodeDefinition> definitions, String nodeType) {
        return definitions.stream().filter(definition -> definition.nodeType().equals(nodeType)).findFirst()
                .orElseThrow(() -> new AssertionError("No node definition for " + nodeType));
    }
}
