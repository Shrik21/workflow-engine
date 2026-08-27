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
 * Azure Virtual Network Gateway connections.
 *
 * <p>Reads a connection's status through Azure Resource Manager:
 * {@code GET /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Network/connections/{name}}. Azure
 * reports {@code properties.connectionStatus} as {@code Connected}, {@code Connecting}, {@code NotConnected}
 * or {@code Unknown}, which map cleanly onto the standard vocabulary.
 */
public final class AzureVpnProvider extends BearerJsonProvider {

    public AzureVpnProvider(CloudHttp http) {
        super(http);
    }

    @Override
    public String id() {
        return "AZURE";
    }

    @Override
    public String label() {
        return "Azure VPN Gateway";
    }

    @Override
    String statusUrl(VpnConnectionRequest request) {
        String host = request.setting("endpointHost", "management.azure.com");
        String apiVersion = request.setting("apiVersion", "2023-09-01");
        String subscription = require(request, "subscriptionId");
        String resourceGroup = require(request, "resourceGroup");
        String connection = require(request, "connectionName");
        return "https://" + host + "/subscriptions/" + subscription
                + "/resourceGroups/" + resourceGroup
                + "/providers/Microsoft.Network/connections/" + connection
                + "?api-version=" + apiVersion;
    }

    @Override
    String providerState(Object json) {
        return Json.text(json, "properties.connectionStatus");
    }

    @Override
    VpnStatus map(String providerState) {
        return switch (providerState.toLowerCase(Locale.ROOT)) {
            case "connected" -> VpnStatus.CONNECTED;
            case "connecting" -> VpnStatus.CONNECTING;
            case "notconnected" -> VpnStatus.DISCONNECTED;
            default -> VpnStatus.UNKNOWN;
        };
    }

    @Override
    Map<String, Object> describe(Object json) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connectionType", Json.text(json, "properties.connectionType"));
        attributes.put("egressBytesTransferred", Json.text(json, "properties.egressBytesTransferred"));
        attributes.put("ingressBytesTransferred", Json.text(json, "properties.ingressBytesTransferred"));
        attributes.put("location", Json.text(json, "location"));
        return attributes;
    }

    private static String require(VpnConnectionRequest request, String key) {
        return request.optionalSetting(key).orElseThrow(() -> new VpnOperationException(
                "VPN_CONFIGURATION_INVALID", "Azure requires '" + key + "'.", false));
    }
}
