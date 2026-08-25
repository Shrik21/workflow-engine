package com.orchpilot.workflow.plugins.vpn.provider.aws;

import com.orchpilot.workflow.plugins.vpn.provider.CloudHttp;
import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.TunnelState;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionInfo;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AWS Site-to-Site VPN.
 *
 * <h2>There is no connect, and this does not pretend there is</h2>
 *
 * An AWS Site-to-Site VPN is always-on infrastructure. Its two tunnels come up when the customer gateway
 * device negotiates IKE with AWS; there is no API that "connects" one, and the spec is explicit that this
 * plugin must not invent one. So {@code connect} here calls {@code DescribeVpnConnections}, reads the tunnel
 * telemetry AWS actually exposes, and reports the state — its message says as much. {@code disconnect} is an
 * honest refusal, because the only way to stop an AWS VPN connection is to delete it, which is a different,
 * destructive operation.
 *
 * <h2>State comes from tunnel telemetry, not a guess</h2>
 *
 * A connection has two tunnels. AWS reports each as {@code UP} or {@code DOWN} in {@code vgwTelemetry}. One
 * tunnel up is a working connection — the second is for redundancy — so the standard status is CONNECTED when
 * any tunnel is UP, FAILED when the connection is available but both are DOWN, and mapped from the
 * connection's own {@code state} ({@code pending}, {@code deleting}, {@code deleted}) otherwise.
 *
 * <p>Calls go through {@link CloudHttp} and are signed with {@link SigV4}; nothing here uses an AWS SDK.
 */
public final class AwsVpnProvider implements VpnProvider {

    private static final String EC2_API_VERSION = "2016-11-15";

    private final CloudHttp http;

    public AwsVpnProvider(CloudHttp http) {
        this.http = http;
    }

    @Override
    public String id() {
        return "AWS";
    }

    @Override
    public String label() {
        return "AWS Site-to-Site VPN";
    }

    @Override
    public Set<String> supportedOperations() {
        // CONNECT is accepted and mapped to describe-and-report (AWS has no dial). DISCONNECT is deliberately
        // absent, so the node refuses it before dispatch rather than returning a fake success — see disconnect().
        return Set.of("CONNECT", "STATUS", "TEST_CONNECTION", "GET_INFO", "WAIT_UNTIL_CONNECTED");
    }

    @Override
    public Set<String> credentialNames() {
        return Set.of("accessKeyId", "secretKey", "sessionToken");
    }

    @Override
    public VpnConnectionResult connect(VpnConnectionRequest request) {
        VpnConnectionStatus status = getStatus(request);
        return new VpnConnectionResult(
                status.status() == VpnStatus.CONNECTED,
                status.status(),
                request.connectionId(),
                "AWS Site-to-Site VPN has no connect operation — the tunnels come up when the customer "
                        + "gateway negotiates IKE. Reporting the current tunnel state: " + status.providerState()
                        + ".",
                status.details());
    }

    @Override
    public VpnConnectionResult disconnect(VpnConnectionRequest request) {
        // Deliberately not a fake success. Stopping an AWS VPN connection means deleting it (DELETE), which is
        // destructive and separate. Saying "disconnected" here would be a lie a workflow would act on.
        throw new VpnOperationException("VPN_UNSUPPORTED_OPERATION",
                "AWS Site-to-Site VPN has no disconnect operation. To stop the connection, delete it with the "
                        + "DELETE operation, which is destructive; to influence traffic, change routing on the "
                        + "gateway. This plugin will not report a disconnect that did not happen.", false);
    }

    @Override
    public VpnConnectionStatus getStatus(VpnConnectionRequest request) {
        Object parsed = describe(request);
        String connectionState = xmlText(parsed, "state");
        List<TunnelState> tunnels = tunnels(parsed);

        VpnStatus status;
        if ("deleting".equals(connectionState) || "deleted".equals(connectionState)) {
            status = VpnStatus.DISCONNECTED;
        } else if ("pending".equals(connectionState)) {
            status = VpnStatus.CONNECTING;
        } else if (tunnels.stream().anyMatch(tunnel -> tunnel.status() == VpnStatus.CONNECTED)) {
            status = VpnStatus.CONNECTED;
        } else if (!tunnels.isEmpty()) {
            status = VpnStatus.FAILED;
        } else {
            status = VpnStatus.UNKNOWN;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connectionState", connectionState);
        details.put("tunnelsUp", tunnels.stream().filter(t -> t.status() == VpnStatus.CONNECTED).count());
        details.put("tunnelCount", tunnels.size());
        return new VpnConnectionStatus(status, connectionState, request.connectionId(), tunnels, details);
    }

    @Override
    public VpnConnectionTestResult testConnection(VpnConnectionRequest request) {
        VpnConnectionStatus status = getStatus(request);
        long up = status.tunnels().stream().filter(t -> t.status() == VpnStatus.CONNECTED).count();
        // Honest about what was tested: this read the control-plane telemetry, it did not send a packet
        // through the tunnel.
        String checked = "AWS DescribeVpnConnections tunnel telemetry (control plane; no data-plane probe)";
        boolean healthy = status.status() == VpnStatus.CONNECTED;
        return new VpnConnectionTestResult(healthy, status.status(), checked, null,
                healthy ? up + " of " + status.tunnels().size() + " tunnels are UP."
                        : "No tunnel is UP; the connection state is " + status.providerState() + ".");
    }

    @Override
    public VpnConnectionInfo getConnectionInfo(VpnConnectionRequest request) {
        Object parsed = describe(request);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connectionState", xmlText(parsed, "state"));
        attributes.put("customerGatewayId", xmlText(parsed, "customerGatewayId"));
        attributes.put("vpnGatewayId", xmlText(parsed, "vpnGatewayId"));
        attributes.put("transitGatewayId", xmlText(parsed, "transitGatewayId"));
        attributes.put("category", xmlText(parsed, "category"));
        VpnStatus status = getStatus(request).status();
        return new VpnConnectionInfo(request.connectionId(), id(), status, attributes);
    }

    // ---- AWS EC2 query API through the engine's HTTP client, SigV4 signed ----

    /** Calls DescribeVpnConnections for the configured connection id and parses the XML reply. */
    private Object describe(VpnConnectionRequest request) {
        String connectionId = require(request.connectionId(), "a VPN connection id (vpn-…)");
        String region = require(request.region(), "an AWS region");
        String host = request.setting("endpointHost", "ec2." + region + ".amazonaws.com");

        String body = "Action=DescribeVpnConnections"
                + "&Version=" + EC2_API_VERSION
                + "&VpnConnectionId.1=" + urlEncode(connectionId);

        HttpResponseView response = signedPost(request, host, region, body,
                "AWS DescribeVpnConnections");
        if (!CloudHttp.ok(response)) {
            throw CloudHttp.failure(response, "AWS DescribeVpnConnections");
        }
        return Xml.parse(response.body());
    }

    private HttpResponseView signedPost(VpnConnectionRequest request, String host, String region, String body,
                                        String what) {
        String accessKeyId = request.secret("accessKeyId").orElseThrow(() -> missing("accessKeyId"));
        String secretKey = request.secret("secretKey").orElseThrow(() -> missing("secretKey"));
        String sessionToken = request.secret("sessionToken").orElse(null);

        SigV4 signer = new SigV4(accessKeyId, secretKey, sessionToken, region, "ec2");
        SigV4.SignedHeaders signed = signer.sign("POST", host, "/", "", body, ZonedDateTime.now());

        HttpRequestSpec.Builder spec = HttpRequestSpec.builder("POST", "https://" + host + "/")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        signed.headers().forEach(spec::header);
        return http.send(spec.body(body).build(), what);
    }

    private List<TunnelState> tunnels(Object parsed) {
        List<TunnelState> tunnels = new ArrayList<>();
        // vgwTelemetry is one element containing an <item> per tunnel; the tunnels are the items, not the
        // wrapper. Scoping to the items inside vgwTelemetry avoids picking up the many other <item> elements
        // in the response (the connection set is a list of items too).
        for (Object telemetry : Xml.all(parsed, "vgwTelemetry")) {
            for (Object item : Xml.all(telemetry, "item")) {
                String outside = Xml.text(item, "outsideIpAddress");
                String state = Xml.text(item, "status");
                VpnStatus status = "UP".equalsIgnoreCase(state) ? VpnStatus.CONNECTED : VpnStatus.FAILED;
                tunnels.add(new TunnelState(outside, status, state));
            }
        }
        return tunnels;
    }

    private static String xmlText(Object parsed, String tag) {
        return Xml.text(parsed, tag);
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new VpnOperationException("VPN_CONFIGURATION_INVALID",
                    "AWS requires " + what + ".", false);
        }
        return value;
    }

    private static VpnOperationException missing(String credential) {
        return new VpnOperationException("VPN_CREDENTIAL_MISSING",
                "AWS requires the credential '" + credential + "', stored as a secret.", false);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Lower-case a status for mapping without a locale surprise. */
    static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
