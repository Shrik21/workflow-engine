package com.orchpilot.workflow.plugins.vpn.provider.cloud;

import com.orchpilot.workflow.plugins.vpn.provider.CloudHttp;
import com.orchpilot.workflow.plugins.vpn.provider.Json;
import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Google Cloud HA VPN tunnels.
 *
 * <p>Reads a tunnel through the Compute API:
 * {@code GET /compute/v1/projects/{project}/regions/{region}/vpnTunnels/{tunnel}}. GCP reports {@code status}
 * with a richer set of states than the other clouds — {@code ESTABLISHED}, {@code FIRST_HANDSHAKE},
 * {@code WAITING_FOR_FULL_CONFIG}, {@code AUTHORIZATION_ERROR}, {@code NO_INCOMING_PACKETS} and more — which
 * this maps down to the standard vocabulary, keeping GCP's own word in {@code detailedStatus} for the
 * operator.
 */
public final class GcpVpnProvider extends BearerJsonProvider {

    public GcpVpnProvider(CloudHttp http) {
        super(http);
    }

    @Override
    public String id() {
        return "GCP";
    }

    @Override
    public String label() {
        return "Google Cloud HA VPN";
    }

    @Override
    String statusUrl(VpnConnectionRequest request) {
        String host = request.setting("endpointHost", "compute.googleapis.com");
        String project = require(request, "project");
        String region = request.region() == null || request.region().isBlank()
                ? require(request, "region") : request.region();
        String tunnel = require(request, "tunnel");
        return "https://" + host + "/compute/v1/projects/" + project
                + "/regions/" + region + "/vpnTunnels/" + tunnel;
    }

    @Override
    String providerState(Object json) {
        return Json.text(json, "status");
    }

    @Override
    VpnStatus map(String providerState) {
        return switch (providerState.toUpperCase(Locale.ROOT)) {
            case "ESTABLISHED" -> VpnStatus.CONNECTED;
            case "PROVISIONING", "ALLOCATING_RESOURCES", "WAITING_FOR_FULL_CONFIG",
                    "FIRST_HANDSHAKE", "NEGOTIATION_ONGOING" -> VpnStatus.CONNECTING;
            case "DEPROVISIONING" -> VpnStatus.DISCONNECTING;
            case "STOPPED", "NO_INCOMING_PACKETS" -> VpnStatus.DISCONNECTED;
            case "AUTHORIZATION_ERROR", "NEGOTIATION_FAILURE", "REJECTED", "FAILED" -> VpnStatus.FAILED;
            default -> VpnStatus.UNKNOWN;
        };
    }

    @Override
    Map<String, Object> describe(Object json) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("detailedStatus", Json.text(json, "detailedStatus"));
        attributes.put("peerIp", Json.text(json, "peerIp"));
        attributes.put("vpnGateway", Json.text(json, "vpnGateway"));
        attributes.put("ikeVersion", Json.text(json, "ikeVersion"));
        return attributes;
    }

    private static String require(VpnConnectionRequest request, String key) {
        return request.optionalSetting(key).orElseThrow(() -> new VpnOperationException(
                "VPN_CONFIGURATION_INVALID", "GCP requires '" + key + "'.", false));
    }
}
