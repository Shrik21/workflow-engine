package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin end to end, driven through a mocked {@link PluginContext} and a scripted HTTP client — no Google
 * account, no network. Covers the acceptance-critical paths: the node catalogue and its risk flags, a read, a
 * create that returns an operation, the delete confirmation gate, and a permission error mapped to a clean failure.
 */
class GcpComputeInstancePluginTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String TOKEN_OK = "{\"access_token\":\"ya29.tok\",\"expires_in\":3600}";

    private GcpComputeInstancePlugin plugin;
    private FakeHttpClient http;
    private PluginContext context;

    @BeforeEach
    void setUp() {
        http = new FakeHttpClient();
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn(
                TestServiceAccount.json("vm@test.iam.gserviceaccount.com", TOKEN_URI));

        context = mock(PluginContext.class);
        lenient().when(context.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(context.secrets()).thenReturn(secrets);
        lenient().when(context.http()).thenReturn(http);
        lenient().when(context.dataStore()).thenReturn(mock(PluginDataStore.class));

        plugin = new GcpComputeInstancePlugin();
        plugin.initialize(context);
    }

    private NodeExecutionContext ctx(String nodeType, Map<String, Object> config) {
        NodeExecutionContext c = mock(NodeExecutionContext.class);
        lenient().when(c.nodeType()).thenReturn(nodeType);
        lenient().when(c.configuration()).thenReturn(new MapConfiguration(config));
        lenient().when(c.executionId()).thenReturn("exec-1");
        lenient().when(c.workflowId()).thenReturn("wf-1");
        lenient().when(c.nodeId()).thenReturn("node-1");
        lenient().when(c.attempt()).thenReturn(1);
        lenient().when(c.timeoutMillis()).thenReturn(30_000L);
        lenient().when(c.isCancelled()).thenReturn(false);
        lenient().when(c.currentUser()).thenReturn(Optional.empty());
        return c;
    }

    private Map<String, Object> base(String... extra) {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("credentialsSecret", "gcp.test");
        cfg.put("projectId", "test-project");
        cfg.put("zone", "asia-south1-a");
        cfg.put("instanceName", "orchpilot-vm");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            cfg.put(extra[i], extra[i + 1]);
        }
        return cfg;
    }

    @Test
    void catalogueHasTenNodesWithCorrectRiskFlags() {
        var definitions = plugin.getNodeDefinitions();
        assertThat(definitions).hasSize(10);
        assertThat(definitions).allMatch(NodeDefinition::supportsAI);

        NodeDefinition delete = definition(definitions, "GCP_COMPUTE_DELETE_INSTANCE");
        assertThat(delete.destructive()).isTrue();
        NodeDefinition get = definition(definitions, "GCP_COMPUTE_GET_INSTANCE");
        assertThat(get.destructive()).isFalse();
        assertThat(get.idempotent()).isTrue();
    }

    @Test
    void getReturnsTheInstanceStatus() {
        http.on("POST " + TOKEN_URI, 200, TOKEN_OK)
                .on("GET " + GcpComputeClient.BASE_URL, 200,
                        "{\"id\":\"123\",\"name\":\"orchpilot-vm\",\"status\":\"RUNNING\","
                                + "\"selfLink\":\"https://x/vm\"}");

        NodeExecutionResult result = plugin.execute(ctx("GCP_COMPUTE_GET_INSTANCE", base()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("status", "RUNNING");
        assertThat(result.outputs()).containsEntry("instanceId", "123");
        assertThat(result.outputs()).containsEntry("instanceName", "orchpilot-vm");
    }

    @Test
    void createWithoutWaitingReturnsTheOperationId() {
        http.on("POST " + TOKEN_URI, 200, TOKEN_OK)
                // The pre-existence check must see "not found" so the create proceeds.
                .on("GET " + GcpComputeClient.BASE_URL, 404, "{\"error\":{\"message\":\"not found\"}}")
                .on("POST " + GcpComputeClient.BASE_URL, 200,
                        "{\"name\":\"op-123\",\"targetId\":\"999\",\"targetLink\":\"https://x/vm\"}");

        Map<String, Object> cfg = base("imageProject", "ubuntu-os-cloud", "imageFamily", "ubuntu-2404-lts-amd64",
                "waitForCompletion", "false");
        NodeExecutionResult result = plugin.execute(ctx("GCP_COMPUTE_CREATE_INSTANCE", cfg));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("operationId", "op-123");
        assertThat(result.outputs()).containsEntry("status", "PROVISIONING");
    }

    @Test
    void deleteRequiresConfirmation() {
        NodeExecutionResult blocked = plugin.execute(ctx("GCP_COMPUTE_DELETE_INSTANCE", base()));
        assertThat(blocked.isFailed()).isTrue();
        assertThat(blocked.errorCode()).isEqualTo("GCP_CONFIRMATION_REQUIRED");
        // No HTTP was attempted — the gate is before any GCP call.
        assertThat(http.requests).isEmpty();
    }

    @Test
    void permissionDeniedBecomesACleanFailure() {
        http.on("POST " + TOKEN_URI, 200, TOKEN_OK)
                .on("GET " + GcpComputeClient.BASE_URL, 403,
                        "{\"error\":{\"message\":\"compute.instances.get denied\"}}");

        NodeExecutionResult result = plugin.execute(ctx("GCP_COMPUTE_GET_INSTANCE", base()));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.errorCode()).isEqualTo("GCP_PERMISSION_DENIED");
        assertThat(result.errorMessage()).doesNotContain("ya29");
    }

    private static NodeDefinition definition(java.util.List<NodeDefinition> definitions, String nodeType) {
        return definitions.stream().filter(d -> d.nodeType().equals(nodeType)).findFirst().orElseThrow();
    }
}
