package com.orchpilot.plugin.gcp.network;

import com.orchpilot.plugin.gcp.network.model.NetworkOperation;
import com.orchpilot.plugin.gcp.network.support.FakeHttpClient;
import com.orchpilot.plugin.gcp.network.support.TestSupport;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * The plugin end to end over a scripted Compute API — no GCP account, no network.
 *
 * <p>Weighted towards the things that would be expensive to discover in production: the risk flags the
 * approval engine depends on, the credential never escaping, the confirmation and dependency gates, and the
 * firewall exposure assessment actually blocking rather than merely reporting.
 */
class GcpNetworkPluginTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String TOKEN_OK = "{\"access_token\":\"ya29.test\",\"expires_in\":3600}";
    private static final String COMPUTE = "https://compute.googleapis.com/compute/v1";
    private static final String PROJECT = "test-project";
    private static final String DONE = "{\"name\":\"operation-1\",\"status\":\"DONE\"}";

    private GcpNetworkPlugin plugin;
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

        plugin = new GcpNetworkPlugin();
        plugin.initialize(context);
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    @DisplayName("every operation is published with its risk flag")
    void catalogue() {
        List<NodeDefinition> definitions = plugin.getNodeDefinitions();

        assertThat(definitions).hasSize(NetworkOperation.values().length);
        assertThat(definitions).allMatch(NodeDefinition::supportsAI);
        // One category, so the palette collapses all of them into a single GCP Network row.
        assertThat(definitions).allMatch(d -> "GCP Network".equals(d.category()));

        // The approval engine keys off destructive, so these are the contract.
        assertThat(definition(definitions, "GCP_NET_DELETE_VPC").destructive()).isTrue();
        assertThat(definition(definitions, "GCP_NET_CREATE_FIREWALL").destructive()).isTrue();
        assertThat(definition(definitions, "GCP_NET_LIST_VPCS").destructive()).isFalse();

        assertThat(definition(definitions, "GCP_NET_GET_VPC").idempotent()).isTrue();
        assertThat(definition(definitions, "GCP_NET_CREATE_VPC").idempotent()).isFalse();
    }

    @Test
    @DisplayName("risk levels match what the specification asked for")
    void riskLevels() {
        assertThat(NetworkOperation.LIST_VPCS.risk()).isEqualTo(NetworkOperation.Risk.READ);
        assertThat(NetworkOperation.CREATE_SUBNET.risk()).isEqualTo(NetworkOperation.Risk.MEDIUM);
        assertThat(NetworkOperation.UPDATE_VPC.risk()).isEqualTo(NetworkOperation.Risk.HIGH);
        assertThat(NetworkOperation.CREATE_FIREWALL.risk()).isEqualTo(NetworkOperation.Risk.HIGH);
        assertThat(NetworkOperation.DELETE_VPC.risk()).isEqualTo(NetworkOperation.Risk.CRITICAL);
    }

    // ------------------------------------------------------------------ VPC

    @Test
    @DisplayName("creates a custom-mode VPC and does not create subnets unless asked")
    void createsVpc() {
        http.on("POST " + COMPUTE + "/projects/" + PROJECT + "/global/networks", 200, DONE);
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/prod-vpc", 200,
                "{\"name\":\"prod-vpc\",\"routingConfig\":{\"routingMode\":\"GLOBAL\"},"
                        + "\"subnetworks\":[]}");

        NodeExecutionResult result = run("GCP_NET_CREATE_VPC",
                cfg("vpcName", "prod-vpc", "routingMode", "GLOBAL"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("mode", "CUSTOM").containsEntry("vpcName", "prod-vpc");
        // The default must be custom mode: auto silently creates a subnet in every region.
        assertThat(http.lastMatching("POST", "/global/networks").body())
                .contains("\"autoCreateSubnetworks\":false");
    }

    @Test
    @DisplayName("waits for the long-running operation before reporting success")
    void waitsForOperation() {
        http.on("POST " + COMPUTE + "/projects/" + PROJECT + "/global/networks", 200,
                "{\"name\":\"operation-9\",\"status\":\"RUNNING\"}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/operations/operation-9", 200, DONE);
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/vpc-1", 200,
                "{\"name\":\"vpc-1\"}");

        NodeExecutionResult result = run("GCP_NET_CREATE_VPC", cfg("vpcName", "vpc-1"));

        assertThat(result.isSuccess()).isTrue();
        // "Accepted" is not "exists"; the next node in a workflow needs the latter.
        assertThat(http.countMatching("/global/operations/operation-9")).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed operation is reported as a failure, not as success")
    void failedOperationIsAFailure() {
        http.on("POST " + COMPUTE + "/projects/" + PROJECT + "/global/networks", 200,
                "{\"name\":\"op\",\"status\":\"DONE\",\"error\":{\"errors\":["
                        + "{\"message\":\"The resource already exists\"}]}}");

        NodeExecutionResult result = run("GCP_NET_CREATE_VPC", cfg("vpcName", "vpc-1"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GCP_OPERATION_FAILED");
        assertThat(result.errorMessage()).contains("already exists");
    }

    @Test
    @DisplayName("lists VPCs")
    void listsVpcs() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks", 200,
                "{\"items\":[{\"name\":\"a\",\"autoCreateSubnetworks\":true},{\"name\":\"b\"}]}");

        NodeExecutionResult result = run("GCP_NET_LIST_VPCS", cfg());

        assertThat(result.outputs()).containsEntry("count", 2);
        List<?> items = (List<?>) result.outputs().get("items");
        assertThat(map(items.get(0))).containsEntry("mode", "AUTO");
        assertThat(map(items.get(1))).containsEntry("mode", "CUSTOM");
    }

    // ------------------------------------------------------------------ the gates

    @Test
    @DisplayName("a destructive operation is refused before any call is made")
    void refusesUnconfirmedDelete() {
        NodeExecutionResult result = run("GCP_NET_DELETE_VPC", cfg("vpcName", "prod-vpc"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GCP_CONFIRMATION_REQUIRED");
        // Nothing may reach GCP before confirmation — not even the dependency check.
        assertThat(http.countMatching("compute.googleapis.com")).isZero();
    }

    @Test
    @DisplayName("a VPC with dependents is not deleted, and they are all named at once")
    void refusesDeleteWithDependents() {
        // Registered before the defaults: the fake answers with the first matching rule, so an override has
        // to come first or it never fires.
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/subnetworks", 200,
                "{\"items\":{\"regions/asia-south1\":{\"subnetworks\":[{\"name\":\"s1\","
                        + "\"network\":\"https://x/networks/prod-vpc\"}]}}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls", 200,
                "{\"items\":[{\"name\":\"f1\",\"network\":\"https://x/networks/prod-vpc\"}]}");
        givenEmptyDependencies();

        NodeExecutionResult result = run("GCP_NET_DELETE_VPC",
                cfg("vpcName", "prod-vpc", "confirmed", true));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GCP_NETWORK_HAS_DEPENDENCIES");
        // Both in one message: one dependent at a time means one failed run per dependent.
        assertThat(result.errorMessage()).contains("1 subnet(s)").contains("1 firewall rule(s)");
        assertThat(result.errorMessage()).contains("never deletes dependents");
        assertThat(http.countMatching("DELETE")).isZero();
    }

    @Test
    @DisplayName("a clean VPC deletes")
    void deletesCleanVpc() {
        givenEmptyDependencies();
        http.on("DELETE " + COMPUTE + "/projects/" + PROJECT + "/global/networks/prod-vpc", 200, DONE);

        NodeExecutionResult result = run("GCP_NET_DELETE_VPC",
                cfg("vpcName", "prod-vpc", "confirmed", true));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("deleted", true);
    }

    @Test
    @DisplayName("Compute's own default routes do not block a delete")
    void defaultRoutesAreNotDependents() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/routes", 200,
                "{\"items\":[{\"name\":\"default-route-abc\",\"network\":\"https://x/networks/prod-vpc\"}]}");
        http.on("DELETE " + COMPUTE + "/projects/" + PROJECT + "/global/networks/prod-vpc", 200, DONE);
        givenEmptyDependencies();

        // These disappear with the network; treating them as dependents would make deletion impossible.
        assertThat(run("GCP_NET_DELETE_VPC", cfg("vpcName", "prod-vpc", "confirmed", true)).isSuccess())
                .isTrue();
    }

    // ------------------------------------------------------------------ subnet

    @Test
    @DisplayName("validates the CIDR before calling GCP")
    void validatesCidrLocally() {
        NodeExecutionResult result = run("GCP_NET_CREATE_SUBNET",
                cfg("region", "asia-south1", "vpcName", "vpc", "subnetName", "s",
                        "ipCidrRange", "10.0.0.5/24"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GCP_INVALID_CIDR");
        // Rejected before the round trip, so no request was made at all.
        assertThat(http.countMatching("compute.googleapis.com")).isZero();
    }

    @Test
    @DisplayName("creates a subnet with secondary ranges")
    void createsSubnet() {
        String path = COMPUTE + "/projects/" + PROJECT + "/regions/asia-south1/subnetworks";
        http.on("POST " + path, 200, DONE);
        http.on("GET " + path + "/app", 200,
                "{\"name\":\"app\",\"ipCidrRange\":\"10.10.0.0/24\",\"region\":\"https://x/regions/"
                        + "asia-south1\",\"secondaryIpRanges\":[{\"rangeName\":\"pods\","
                        + "\"ipCidrRange\":\"10.20.0.0/16\"}]}");

        NodeExecutionResult result = run("GCP_NET_CREATE_SUBNET",
                cfg("region", "asia-south1", "vpcName", "vpc", "subnetName", "app",
                        "ipCidrRange", "10.10.0.0/24", "privateGoogleAccess", true,
                        "secondaryIpRanges", List.of(
                                Map.of("rangeName", "pods", "ipCidrRange", "10.20.0.0/16"))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("region", "asia-south1");
        assertThat(http.lastMatching("POST", "/subnetworks").body())
                .contains("\"privateIpGoogleAccess\":true").contains("\"rangeName\":\"pods\"");
    }

    @Test
    @DisplayName("a bad secondary range is caught too")
    void validatesSecondaryRanges() {
        NodeExecutionResult result = run("GCP_NET_CREATE_SUBNET",
                cfg("region", "asia-south1", "vpcName", "vpc", "subnetName", "app",
                        "ipCidrRange", "10.10.0.0/24",
                        "secondaryIpRanges", List.of(
                                Map.of("rangeName", "pods", "ipCidrRange", "not-a-cidr"))));

        assertThat(result.errorCode()).isEqualTo("GCP_INVALID_CIDR");
        assertThat(result.errorMessage()).contains("pods");
    }

    // ------------------------------------------------------------------ firewall

    @Test
    @DisplayName("an unconfirmed rule opening SSH to the internet is refused, with the reason")
    void refusesUnconfirmedPublicSsh() {
        NodeExecutionResult result = run("GCP_NET_CREATE_FIREWALL",
                cfg("firewallName", "allow-ssh", "network", "vpc", "protocol", "tcp",
                        "ports", "22", "sourceRanges", "0.0.0.0/0"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GCP_CONFIRMATION_REQUIRED");
        assertThat(result.errorMessage()).contains("SSH").contains("entire internet");
        assertThat(http.countMatching("/global/firewalls")).isZero();
    }

    @Test
    @DisplayName("confirmed, it proceeds and the finding travels in the output")
    void confirmedPublicSshProceeds() {
        http.on("POST " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls", 200, DONE);
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls/allow-ssh", 200,
                "{\"name\":\"allow-ssh\",\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"22\"]}],"
                        + "\"sourceRanges\":[\"0.0.0.0/0\"]}");

        NodeExecutionResult result = run("GCP_NET_CREATE_FIREWALL",
                cfg("firewallName", "allow-ssh", "network", "vpc", "protocol", "tcp",
                        "ports", "22", "sourceRanges", "0.0.0.0/0", "confirmed", true));

        assertThat(result.isSuccess()).isTrue();
        // Never silently blocked, and never silently allowed either: the finding is in the record.
        assertThat(result.outputs()).containsEntry("exposesAdministrativeAccess", true);
        assertThat((List<?>) result.outputs().get("securityFindings")).hasSize(1);
    }

    @Test
    @DisplayName("an ordinary web rule needs no confirmation")
    void webRuleNeedsNoConfirmation() {
        http.on("POST " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls", 200, DONE);
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls/allow-web", 200,
                "{\"name\":\"allow-web\",\"allowed\":[{\"IPProtocol\":\"tcp\",\"ports\":[\"443\"]}]}");

        NodeExecutionResult result = run("GCP_NET_CREATE_FIREWALL",
                cfg("firewallName", "allow-web", "network", "vpc", "protocol", "tcp",
                        "ports", "443", "sourceRanges", "0.0.0.0/0", "confirmed", true));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("exposesAdministrativeAccess", false);
    }

    // ------------------------------------------------------------------ NAT

    @Test
    @DisplayName("NAT is written as a read-modify-write of its router")
    void createsNat() {
        String router = COMPUTE + "/projects/" + PROJECT + "/regions/asia-south1/routers/rtr";
        http.on("GET " + router, 200, "{\"name\":\"rtr\",\"nats\":[]}");
        http.on("PATCH " + router, 200, DONE);

        NodeExecutionResult result = run("GCP_NET_CREATE_NAT",
                cfg("region", "asia-south1", "routerName", "rtr", "natName", "nat-1"));

        assertThat(result.isSuccess()).isTrue();
        // NAT is not a resource of its own; it lives in the router's nats[].
        assertThat(http.lastMatching("PATCH", "/routers/rtr").body())
                .contains("\"nats\"").contains("\"nat-1\"").contains("AUTO_ONLY");
    }

    @Test
    @DisplayName("a duplicate NAT name is refused rather than silently added twice")
    void refusesDuplicateNat() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/regions/asia-south1/routers/rtr", 200,
                "{\"name\":\"rtr\",\"nats\":[{\"name\":\"nat-1\"}]}");

        NodeExecutionResult result = run("GCP_NET_CREATE_NAT",
                cfg("region", "asia-south1", "routerName", "rtr", "natName", "nat-1"));

        assertThat(result.errorCode()).isEqualTo("GCP_RESOURCE_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("a MANUAL_ONLY NAT without addresses is refused locally")
    void manualNatNeedsAddresses() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/regions/asia-south1/routers/rtr", 200,
                "{\"name\":\"rtr\",\"nats\":[]}");

        NodeExecutionResult result = run("GCP_NET_CREATE_NAT",
                cfg("region", "asia-south1", "routerName", "rtr", "natName", "n",
                        "natIpAllocateOption", "MANUAL_ONLY"));

        assertThat(result.errorCode()).isEqualTo("GCP_INVALID_ARGUMENT");
        assertThat(result.errorMessage()).contains("natIps");
    }

    // ------------------------------------------------------------------ inspection

    @Test
    @DisplayName("inspection returns the VPC and everything attached, with counts")
    void inspects() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/prod-vpc", 200,
                "{\"name\":\"prod-vpc\",\"peerings\":[{\"name\":\"p1\",\"state\":\"ACTIVE\"}]}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/subnetworks", 200,
                "{\"items\":{\"regions/asia-south1\":{\"subnetworks\":[{\"name\":\"s1\","
                        + "\"network\":\"https://x/networks/prod-vpc\"}]}}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls", 200, "{\"items\":[]}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/routes", 200, "{\"items\":[]}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/routers", 200,
                "{\"items\":{\"regions/asia-south1\":{\"routers\":[{\"name\":\"r1\","
                        + "\"network\":\"https://x/networks/prod-vpc\",\"nats\":[{\"name\":\"n1\"}]}]}}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/instances", 200, "{\"items\":{}}");

        NodeExecutionResult result = run("GCP_NET_INSPECT", cfg("vpcName", "prod-vpc"));

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> counts = map(result.outputs().get("counts"));
        assertThat(counts).containsEntry("subnets", 1).containsEntry("routers", 1)
                .containsEntry("nats", 1).containsEntry("peerings", 1);
    }

    @Test
    @DisplayName("inspection survives a permission gap on one resource type and says which")
    void inspectionIsPartialRatherThanFailing() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/prod-vpc", 200,
                "{\"name\":\"prod-vpc\"}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/subnetworks", 200,
                "{\"items\":{}}");
        // No permission to list firewall rules.
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls", 403,
                "{\"error\":{\"message\":\"compute.firewalls.list denied\"}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/routes", 200, "{\"items\":[]}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/routers", 200, "{\"items\":{}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/instances", 200, "{\"items\":{}}");

        NodeExecutionResult result = run("GCP_NET_INSPECT", cfg("vpcName", "prod-vpc"));

        // A partial picture beats none, provided it says it is partial.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("partial", true);
        assertThat((List<?>) result.outputs().get("unavailable")).isNotEmpty();
    }

    // ------------------------------------------------------------------ errors and secrets

    @Test
    @DisplayName("maps GCP status codes onto the documented error codes")
    void mapsErrors() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/gone", 404,
                "{\"error\":{\"message\":\"The resource was not found\"}}");
        NodeExecutionResult notFound = run("GCP_NET_GET_VPC", cfg("vpcName", "gone"));
        assertThat(notFound.errorCode()).isEqualTo("GCP_RESOURCE_NOT_FOUND");
        assertThat(notFound.retryable()).isFalse();

        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/denied", 403,
                "{\"error\":{\"message\":\"Required 'compute.networks.get' permission\"}}");
        NodeExecutionResult denied = run("GCP_NET_GET_VPC", cfg("vpcName", "denied"));
        assertThat(denied.errorCode()).isEqualTo("GCP_PERMISSION_DENIED");
        // Google's message names the exact IAM permission, which is the useful part.
        assertThat(denied.errorMessage()).contains("compute.networks.get");

        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/limited", 429,
                "{\"error\":{\"message\":\"Quota exceeded\"}}");
        NodeExecutionResult limited = run("GCP_NET_GET_VPC", cfg("vpcName", "limited"));
        assertThat(limited.errorCode()).isEqualTo("GCP_QUOTA_EXCEEDED");
        assertThat(limited.retryable()).isTrue();

        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/broken", 503,
                "{\"error\":{\"message\":\"Backend error\"}}");
        NodeExecutionResult broken = run("GCP_NET_GET_VPC", cfg("vpcName", "broken"));
        // Google's problem, not the workflow's — so the engine should try again.
        assertThat(broken.errorCode()).isEqualTo("GCP_API_UNAVAILABLE");
        assertThat(broken.retryable()).isTrue();
    }

    @Test
    @DisplayName("an unrecognised node type fails cleanly instead of throwing")
    void unknownNodeType() {
        NodeExecutionResult result = run("GCP_NET_NOT_A_REAL_OPERATION", cfg());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GCP_OPERATION_FAILED");
        assertThat(result.errorMessage()).contains("GCP_NET_NOT_A_REAL_OPERATION");
    }

    @Test
    @DisplayName("the service-account key never reaches node output")
    void keyNeverReachesOutput() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks", 200, "{\"items\":[]}");

        NodeExecutionResult result = run("GCP_NET_LIST_VPCS", cfg());

        assertThat(Json.write(result.outputs()))
                .doesNotContain("PRIVATE KEY")
                .doesNotContain(TestSupport.SECRET_MARKER_EMAIL)
                .doesNotContain("ya29.test");
    }

    @Test
    @DisplayName("the key is exchanged at the token endpoint and goes nowhere else")
    void keyNeverReachesCompute() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks", 200, "{\"items\":[]}");

        run("GCP_NET_LIST_VPCS", cfg());

        assertThat(http.allTraffic()).doesNotContain("PRIVATE KEY");
    }

    // ------------------------------------------------------------------ helpers

    /** Every dependency probe answering "nothing", so a test can override just the one it cares about. */
    private void givenEmptyDependencies() {
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/subnetworks", 200,
                "{\"items\":{}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/firewalls", 200, "{\"items\":[]}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/routes", 200, "{\"items\":[]}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/routers", 200, "{\"items\":{}}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/global/networks/prod-vpc", 200,
                "{\"name\":\"prod-vpc\"}");
        http.on("GET " + COMPUTE + "/projects/" + PROJECT + "/aggregated/instances", 200, "{\"items\":{}}");
    }

    private NodeExecutionResult run(String nodeType, Map<String, Object> configuration) {
        NodeExecutionContext context = mock(NodeExecutionContext.class);
        lenient().when(context.nodeType()).thenReturn(nodeType);
        lenient().when(context.configuration()).thenReturn(TestSupport.config(configuration));
        lenient().when(context.executionId()).thenReturn("exec-1");
        lenient().when(context.workflowId()).thenReturn("wf-1");
        lenient().when(context.workflowVersion()).thenReturn(1);
        lenient().when(context.nodeId()).thenReturn("node-1");
        lenient().when(context.attempt()).thenReturn(1);
        lenient().when(context.timeoutMillis()).thenReturn(30_000L);
        lenient().when(context.isCancelled()).thenReturn(false);
        lenient().when(context.currentUser()).thenReturn(Optional.empty());
        return plugin.execute(context);
    }

    private static Map<String, Object> cfg(Object... extra) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("connection", "gcp.test");
        configuration.put("project", PROJECT);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            configuration.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return configuration;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static NodeDefinition definition(List<NodeDefinition> definitions, String nodeType) {
        return definitions.stream().filter(d -> d.nodeType().equals(nodeType)).findFirst()
                .orElseThrow(() -> new AssertionError("No node definition for " + nodeType));
    }
}
