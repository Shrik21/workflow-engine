package com.orchpilot.workflow.plugins.vpn.provider;

import com.orchpilot.workflow.plugins.vpn.provider.aws.AwsVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.cloud.AzureVpnProvider;
import com.orchpilot.workflow.plugins.vpn.provider.cloud.GcpVpnProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud providers against canned control-plane responses.
 *
 * <p>No cloud account is reached. What these prove is the part that is provider-specific and easy to get
 * wrong: the request is built and signed, the response's status field is found, and the provider's own state
 * words are mapped onto the standard vocabulary — including the mappings that must not be confused, such as
 * GCP's {@code NO_INCOMING_PACKETS} being a real down state and its {@code FIRST_HANDSHAKE} being in-flight.
 * A live-account test would add nothing to those mappings and could not run here anyway.
 */
class CloudProviderTest {

    /** A stub HTTP client returning one canned response and capturing the request that reached it. */
    private static final class Stub implements Function<HttpRequestSpec, HttpResponseView> {
        private final int status;
        private final String body;
        HttpRequestSpec captured;

        Stub(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public HttpResponseView apply(HttpRequestSpec request) {
            this.captured = request;
            return new HttpResponseView(status, Map.of(), body, 5);
        }
    }

    private static CloudHttp http(Stub stub) {
        return new CloudHttp(stub::apply);
    }

    private static VpnConnectionRequest request(String provider, String connectionId, String region,
                                                Map<String, Object> settings, Map<String, String> secrets) {
        return new VpnConnectionRequest("STATUS", provider, connectionId, region, settings, secrets);
    }

    @Nested
    @DisplayName("AWS")
    class Aws {

        private static final String TWO_TUNNELS_ONE_UP = """
                <DescribeVpnConnectionsResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/">
                  <vpnConnectionSet>
                    <item>
                      <vpnConnectionId>vpn-123</vpnConnectionId>
                      <state>available</state>
                      <customerGatewayId>cgw-1</customerGatewayId>
                      <vpnGatewayId>vgw-1</vpnGatewayId>
                      <vgwTelemetry>
                        <item><outsideIpAddress>52.1.1.1</outsideIpAddress><status>UP</status></item>
                        <item><outsideIpAddress>52.1.1.2</outsideIpAddress><status>DOWN</status></item>
                      </vgwTelemetry>
                    </item>
                  </vpnConnectionSet>
                </DescribeVpnConnectionsResponse>
                """;

        private AwsVpnProvider provider(Stub stub) {
            return new AwsVpnProvider(http(stub));
        }

        private VpnConnectionRequest awsRequest() {
            return request("AWS", "vpn-123", "ap-south-1", Map.of(),
                    Map.of("accessKeyId", "AKIDEXAMPLE", "secretKey", "secret"));
        }

        @Test
        @DisplayName("one tunnel UP is CONNECTED, and the request is a signed EC2 call")
        void connected() {
            Stub stub = new Stub(200, TWO_TUNNELS_ONE_UP);
            VpnConnectionStatus status = provider(stub).getStatus(awsRequest());

            assertEquals(VpnStatus.CONNECTED, status.status());
            assertEquals(2, status.tunnels().size());
            assertEquals(1, status.tunnels().stream().filter(t -> t.status() == VpnStatus.CONNECTED).count());

            // It went to the regional EC2 endpoint, signed.
            assertTrue(stub.captured.uri().contains("ec2.ap-south-1.amazonaws.com"));
            assertTrue(stub.captured.headers().get("Authorization").startsWith("AWS4-HMAC-SHA256"));
            assertTrue(stub.captured.body().contains("DescribeVpnConnections"));
        }

        @Test
        @DisplayName("both tunnels DOWN on an available connection is FAILED")
        void bothDown() {
            String body = TWO_TUNNELS_ONE_UP.replace("<status>UP</status>", "<status>DOWN</status>");
            assertEquals(VpnStatus.FAILED, provider(new Stub(200, body)).getStatus(awsRequest()).status());
        }

        @Test
        @DisplayName("connect reports state and says AWS has no connect operation")
        void connectReportsOnly() {
            var result = provider(new Stub(200, TWO_TUNNELS_ONE_UP)).connect(awsRequest());
            assertEquals(VpnStatus.CONNECTED, result.status());
            assertTrue(result.message().toLowerCase().contains("no connect operation"));
        }

        @Test
        @DisplayName("disconnect is refused rather than faked")
        void disconnectRefused() {
            VpnOperationException failure = assertThrows(VpnOperationException.class,
                    () -> provider(new Stub(200, TWO_TUNNELS_ONE_UP)).disconnect(awsRequest()));
            assertEquals("VPN_UNSUPPORTED_OPERATION", failure.code());
        }

        @Test
        @DisplayName("a 403 is an authentication error, not a generic failure")
        void forbidden() {
            VpnOperationException failure = assertThrows(VpnOperationException.class,
                    () -> provider(new Stub(403, "<Response><Errors/></Response>")).getStatus(awsRequest()));
            assertEquals("VPN_AUTHENTICATION_ERROR", failure.code());
            assertFalse(failure.retryable());
        }

        @Test
        @DisplayName("a missing credential is refused before any call")
        void missingCredential() {
            VpnConnectionRequest noSecret = request("AWS", "vpn-123", "ap-south-1", Map.of(), Map.of());
            VpnOperationException failure = assertThrows(VpnOperationException.class,
                    () -> provider(new Stub(200, "")).getStatus(noSecret));
            assertEquals("VPN_CREDENTIAL_MISSING", failure.code());
        }
    }

    @Nested
    @DisplayName("Azure")
    class Azure {

        private AzureVpnProvider provider(Stub stub) {
            return new AzureVpnProvider(http(stub));
        }

        private VpnConnectionRequest azureRequest() {
            return request("AZURE", "conn", "", Map.of(
                    "subscriptionId", "sub-1", "resourceGroup", "rg-1", "connectionName", "vpn-conn"),
                    Map.of("accessToken", "bearer-token"));
        }

        @Test
        @DisplayName("Connected maps to CONNECTED, with a bearer token on the ARM call")
        void connected() {
            Stub stub = new Stub(200, "{\"properties\":{\"connectionStatus\":\"Connected\"},"
                    + "\"location\":\"centralindia\"}");
            assertEquals(VpnStatus.CONNECTED, provider(stub).getStatus(azureRequest()).status());
            assertTrue(stub.captured.uri().contains("management.azure.com"));
            assertEquals("Bearer bearer-token", stub.captured.headers().get("Authorization"));
        }

        @Test
        @DisplayName("NotConnected maps to DISCONNECTED and Connecting to CONNECTING")
        void otherStates() {
            assertEquals(VpnStatus.DISCONNECTED, provider(new Stub(200,
                    "{\"properties\":{\"connectionStatus\":\"NotConnected\"}}")).getStatus(azureRequest())
                    .status());
            assertEquals(VpnStatus.CONNECTING, provider(new Stub(200,
                    "{\"properties\":{\"connectionStatus\":\"Connecting\"}}")).getStatus(azureRequest())
                    .status());
        }
    }

    @Nested
    @DisplayName("GCP")
    class Gcp {

        private GcpVpnProvider provider(Stub stub) {
            return new GcpVpnProvider(http(stub));
        }

        private VpnConnectionRequest gcpRequest() {
            return request("GCP", "tunnel-1", "asia-south1", Map.of(
                    "project", "proj-1", "tunnel", "tunnel-1"), Map.of("accessToken", "bearer"));
        }

        @Test
        @DisplayName("ESTABLISHED maps to CONNECTED")
        void established() {
            Stub stub = new Stub(200, "{\"status\":\"ESTABLISHED\",\"detailedStatus\":\"Tunnel is up\"}");
            assertEquals(VpnStatus.CONNECTED, provider(stub).getStatus(gcpRequest()).status());
            assertTrue(stub.captured.uri().contains("compute.googleapis.com"));
        }

        @Test
        @DisplayName("GCP's richer states map without confusing in-flight for failed")
        void richStates() {
            assertEquals(VpnStatus.CONNECTING, provider(new Stub(200,
                    "{\"status\":\"FIRST_HANDSHAKE\"}")).getStatus(gcpRequest()).status());
            assertEquals(VpnStatus.FAILED, provider(new Stub(200,
                    "{\"status\":\"AUTHORIZATION_ERROR\"}")).getStatus(gcpRequest()).status());
            assertEquals(VpnStatus.DISCONNECTED, provider(new Stub(200,
                    "{\"status\":\"NO_INCOMING_PACKETS\"}")).getStatus(gcpRequest()).status());
            // A state this plugin has never heard of is UNKNOWN, not FAILED — see VpnStatus.
            assertEquals(VpnStatus.UNKNOWN, provider(new Stub(200,
                    "{\"status\":\"SOME_NEW_STATE\"}")).getStatus(gcpRequest()).status());
        }
    }
}
