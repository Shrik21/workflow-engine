package com.orchpilot.workflow.plugins.vpn;

import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionInfo;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;
import com.orchpilot.workflow.plugins.vpn.support.TestExecution;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's dispatch, output shaping and wait loop, exercised through a fake provider.
 *
 * <p>A fake provider registered under a real id lets these tests drive the node's own logic — parsing,
 * dispatch, the wait loop, the nested outputs — without a network, which is the part that has to be right
 * whatever the provider behind it does.
 */
class VpnNodeTest {

    /** A provider whose every method returns what the test told it to, and records what it was asked. */
    static class FakeProvider implements VpnProvider {
        private final String id;
        Supplier<VpnConnectionStatus> statusSupplier = () -> VpnConnectionStatus.of(VpnStatus.CONNECTED,
                "up", "conn-1");
        VpnConnectionRequest lastRequest;

        FakeProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String label() {
            return "Fake " + id;
        }

        @Override
        public Set<String> supportedOperations() {
            return Set.of("CONNECT", "DISCONNECT", "STATUS", "TEST_CONNECTION", "GET_INFO",
                    "WAIT_UNTIL_CONNECTED");
        }

        @Override
        public Set<String> credentialNames() {
            return Set.of("token");
        }

        @Override
        public VpnConnectionResult connect(VpnConnectionRequest request) {
            lastRequest = request;
            return VpnConnectionResult.ok(VpnStatus.CONNECTED, "conn-1", "connected");
        }

        @Override
        public VpnConnectionResult disconnect(VpnConnectionRequest request) {
            lastRequest = request;
            return VpnConnectionResult.ok(VpnStatus.DISCONNECTED, "conn-1", "disconnected");
        }

        @Override
        public VpnConnectionStatus getStatus(VpnConnectionRequest request) {
            lastRequest = request;
            return statusSupplier.get();
        }

        @Override
        public VpnConnectionTestResult testConnection(VpnConnectionRequest request) {
            lastRequest = request;
            return VpnConnectionTestResult.passed(VpnStatus.CONNECTED, "a real check", 25L, "healthy");
        }

        @Override
        public VpnConnectionInfo getConnectionInfo(VpnConnectionRequest request) {
            lastRequest = request;
            return new VpnConnectionInfo("conn-1", id, VpnStatus.CONNECTED, Map.of("gateway", "gw-1"));
        }
    }

    private final FakeProvider provider = new FakeProvider("AWS");

    private Map<String, Object> node(String operation, Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("provider", "AWS");
        values.put("operation", operation);
        values.put("connectionId", "conn-1");
        values.put("region", "ap-south-1");
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private NodeExecutionResult run(Map<String, Object> configuration) {
        return run(configuration, Map.of(), Map.of());
    }

    private NodeExecutionResult run(Map<String, Object> configuration, Map<String, String> secrets,
                                    Map<String, String> variables) {
        TestExecution execution = TestExecution.with(configuration).secrets(secrets).variables(variables)
                .build();
        // Initialise the plugin against the fake provider by installing a registry the node will use.
        TestVpnPlugin testPlugin = new TestVpnPlugin(provider);
        testPlugin.initialize(execution);
        return testPlugin.execute(execution);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(NodeExecutionResult result, String variable) {
        Object value = result.outputs().get(variable);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @Nested
    @DisplayName("Dispatch")
    class Dispatch {

        @Test
        @DisplayName("connect publishes a nested result under the chosen variable")
        void connect() {
            NodeExecutionResult result = run(node("CONNECT", "outputVariable", "vpn"));

            assertTrue(result.isSuccess(), () -> result.errorMessage());
            Map<String, Object> vpn = resultOf(result, "vpn");
            assertEquals("CONNECTED", vpn.get("status"));
            assertEquals("AWS", vpn.get("provider"));
            assertEquals("conn-1", vpn.get("connectionId"));
            assertEquals(Boolean.TRUE, result.outputs().get("success"));
        }

        @Test
        @DisplayName("output keys never contain a dot, so the execution can be persisted")
        void noDottedKeys() {
            NodeExecutionResult result = run(node("STATUS"));
            for (String key : result.outputs().keySet()) {
                assertFalse(key.contains("."), () -> "output key '" + key + "' contains a dot");
            }
        }

        @Test
        @DisplayName("an unknown provider is refused")
        void unknownProvider() {
            NodeExecutionResult result = run(node("STATUS", "provider", "ORACLE"));
            assertTrue(result.isFailed());
            assertEquals(VpnErrors.UNKNOWN_PROVIDER, result.errorCode());
        }

        @Test
        @DisplayName("an operation the provider does not support is refused before dispatch")
        void unsupportedOperation() {
            provider.statusSupplier = () -> VpnConnectionStatus.of(VpnStatus.CONNECTED, "up", "conn-1");
            FakeProvider limited = new FakeProvider("AWS") {
                @Override
                public Set<String> supportedOperations() {
                    return Set.of("STATUS");
                }
            };
            TestExecution execution = TestExecution.with(node("CONNECT")).build();
            TestVpnPlugin limitedPlugin = new TestVpnPlugin(limited);
            limitedPlugin.initialize(execution);
            NodeExecutionResult result = limitedPlugin.execute(execution);

            assertTrue(result.isFailed());
            assertEquals(VpnErrors.UNSUPPORTED_OPERATION, result.errorCode());
        }

        @Test
        @DisplayName("a missing operation is refused")
        void missingOperation() {
            Map<String, Object> configuration = node("CONNECT");
            configuration.remove("operation");
            NodeExecutionResult result = run(configuration);
            assertTrue(result.isFailed());
            assertEquals(VpnErrors.CONFIGURATION_INVALID, result.errorCode());
        }
    }

    @Nested
    @DisplayName("Variables and credentials")
    class VariablesAndCredentials {

        @Test
        @DisplayName("input variables are resolved into the request")
        void resolvesInput() {
            provider.statusSupplier = () -> VpnConnectionStatus.of(VpnStatus.CONNECTED, "up", "conn-9");
            run(node("STATUS", "connectionId", "${vpn.connectionId}", "region", "${cloud.region}"),
                    Map.of(), Map.of("vpn.connectionId", "vpn-abc", "cloud.region", "eu-west-1"));

            assertEquals("vpn-abc", provider.lastRequest.connectionId());
            assertEquals("eu-west-1", provider.lastRequest.region());
        }

        @Test
        @DisplayName("credentials are resolved from a profile's secrets, never from the workflow")
        void resolvesCredentialsFromProfile() {
            run(node("STATUS", "connectionProfile", "aws-prod"),
                    Map.of("aws-prod.token", "the-secret-token"), Map.of());

            assertEquals("the-secret-token", provider.lastRequest.secret("token").orElse(null));
        }

        @Test
        @DisplayName("a credential the operator did not store is simply absent, not blank-substituted")
        void missingCredentialAbsent() {
            run(node("STATUS", "connectionProfile", "aws-prod"), Map.of(), Map.of());
            assertTrue(provider.lastRequest.secret("token").isEmpty());
        }
    }

    @Nested
    @DisplayName("Wait until connected")
    class WaitUntilConnected {

        @Test
        @DisplayName("returns as soon as the provider reports CONNECTED")
        void connects() {
            AtomicInteger polls = new AtomicInteger();
            provider.statusSupplier = () -> {
                int count = polls.incrementAndGet();
                return VpnConnectionStatus.of(count >= 2 ? VpnStatus.CONNECTED : VpnStatus.CONNECTING,
                        count >= 2 ? "up" : "pending", "conn-1");
            };

            NodeExecutionResult result = run(node("WAIT_UNTIL_CONNECTED",
                    "timeoutSeconds", 5, "pollIntervalSeconds", 1));

            assertTrue(result.isSuccess(), () -> result.errorMessage());
            assertEquals("CONNECTED", resultOf(result, "vpnResult").get("status"));
        }

        @Test
        @DisplayName("times out with VPN_CONNECTION_TIMEOUT, and still publishes the last state")
        void timesOut() {
            provider.statusSupplier = () -> VpnConnectionStatus.of(VpnStatus.CONNECTING, "pending", "conn-1");

            NodeExecutionResult result = run(node("WAIT_UNTIL_CONNECTED",
                    "timeoutSeconds", 1, "pollIntervalSeconds", 1));

            assertTrue(result.isFailed());
            assertEquals(VpnErrors.CONNECTION_TIMEOUT, result.errorCode());
            // The contract: the outputs are still present so a following node can read the timed-out state.
            assertEquals("TIMEOUT", resultOf(result, "vpnResult").get("status"));
        }

        @Test
        @DisplayName("stops waiting the moment the provider reports FAILED")
        void stopsOnFailed() {
            AtomicInteger polls = new AtomicInteger();
            provider.statusSupplier = () -> {
                polls.incrementAndGet();
                return VpnConnectionStatus.of(VpnStatus.FAILED, "down", "conn-1");
            };

            NodeExecutionResult result = run(node("WAIT_UNTIL_CONNECTED",
                    "timeoutSeconds", 30, "pollIntervalSeconds", 1));

            // Not a timeout, and not thirty seconds of polling a connection already called down.
            assertEquals("FAILED", resultOf(result, "vpnResult").get("status"));
            assertEquals(1, polls.get());
        }
    }

    @Test
    @DisplayName("health lists the providers it can dispatch to")
    void health() {
        TestExecution execution = TestExecution.with(node("STATUS")).build();
        TestVpnPlugin testPlugin = new TestVpnPlugin(provider);
        testPlugin.initialize(execution);

        Map<String, Object> health = testPlugin.health();
        assertEquals("RUNNING", health.get("status"));
        assertInstanceOf(List.class, health.get("providers"));
        assertTrue(((List<?>) health.get("providers")).contains("AWS"));
    }
}
