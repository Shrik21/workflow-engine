package com.orchpilot.workflow.plugins.vpn.provider.cloud;

import com.orchpilot.workflow.plugins.vpn.provider.CloudHttp;
import com.orchpilot.workflow.plugins.vpn.provider.Json;
import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionInfo;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;
import com.orchpilot.workflow.sdk.context.HttpResponseView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared shape of the Azure and GCP providers: an OAuth bearer token, a JSON control-plane GET, a status
 * field mapped onto the standard vocabulary.
 *
 * <h2>The token is supplied, not minted here</h2>
 *
 * Azure and GCP both authenticate REST calls with a short-lived OAuth 2.0 bearer token. Minting one is a
 * provider-specific exchange — a client-credentials grant, a metadata-server call for a managed identity —
 * that needs its own secrets and its own network calls, and that this plugin cannot exercise against a real
 * tenant to know it is right. So the token is taken from the secret store under the name {@code accessToken},
 * which keeps the credential out of the workflow, works with whatever the operator's environment already uses
 * to obtain tokens (the CLI, a sidecar, a workload-identity webhook), and does not ship an unverified auth
 * flow pretending to be tested. Where an installation wants the plugin to mint tokens itself, that is a
 * focused addition behind this same interface.
 *
 * <h2>Connect and test are honest control-plane reads</h2>
 *
 * Neither cloud has a dial operation for a gateway connection, so {@code connect} reports state and
 * {@code testConnection} says plainly that it read the control plane, not that it sent a packet.
 */
abstract class BearerJsonProvider implements VpnProvider {

    final CloudHttp http;

    BearerJsonProvider(CloudHttp http) {
        this.http = http;
    }

    /** @return the URL that returns the connection/tunnel resource for this request */
    abstract String statusUrl(VpnConnectionRequest request);

    /** @return the provider's own state string out of the parsed JSON */
    abstract String providerState(Object json);

    /** @return the standard status for a provider state */
    abstract VpnStatus map(String providerState);

    @Override
    public Set<String> credentialNames() {
        return Set.of("accessToken");
    }

    @Override
    public VpnConnectionResult connect(VpnConnectionRequest request) {
        VpnConnectionStatus status = getStatus(request);
        return new VpnConnectionResult(status.status() == VpnStatus.CONNECTED, status.status(),
                request.connectionId(),
                label() + " has no connect operation for a gateway connection; reporting its state: "
                        + status.providerState() + ".", status.details());
    }

    @Override
    public VpnConnectionResult disconnect(VpnConnectionRequest request) {
        throw new VpnOperationException("VPN_UNSUPPORTED_OPERATION",
                label() + " has no disconnect operation for a gateway connection. Stop it by deleting the "
                        + "connection resource, which is destructive, or by changing routing.", false);
    }

    @Override
    public VpnConnectionStatus getStatus(VpnConnectionRequest request) {
        Object json = getJson(request);
        String state = providerState(json);
        VpnStatus status = map(state);
        return new VpnConnectionStatus(status, state, request.connectionId(),
                List.of(), Map.of("providerState", state));
    }

    @Override
    public VpnConnectionInfo getConnectionInfo(VpnConnectionRequest request) {
        Object json = getJson(request);
        Map<String, Object> attributes = new LinkedHashMap<>();
        String state = providerState(json);
        attributes.put("providerState", state);
        attributes.putAll(describe(json));
        return new VpnConnectionInfo(request.connectionId(), id(), map(state), attributes);
    }

    /** @return provider-specific, non-secret descriptive fields for GET_INFO; overridden per provider */
    Map<String, Object> describe(Object json) {
        return Map.of();
    }

    @Override
    public VpnConnectionTestResult testConnection(VpnConnectionRequest request) {
        VpnConnectionStatus status = getStatus(request);
        String checked = label() + " connection status (control plane; no data-plane probe)";
        boolean healthy = status.status() == VpnStatus.CONNECTED;
        return new VpnConnectionTestResult(healthy, status.status(), checked, null,
                healthy ? "The connection reports " + status.providerState() + "."
                        : "The connection state is " + status.providerState() + ".");
    }

    /** Fetches and parses the status resource, classifying an auth or provider failure. */
    Object getJson(VpnConnectionRequest request) {
        String token = request.secret("accessToken").orElseThrow(() -> new VpnOperationException(
                "VPN_CREDENTIAL_MISSING",
                label() + " requires an OAuth bearer token, stored as the secret 'accessToken'.", false));
        String url = statusUrl(request);
        HttpResponseView response = http.get(url,
                Map.of("Authorization", "Bearer " + token, "Accept", "application/json"),
                label() + " connection status");
        if (!CloudHttp.ok(response)) {
            throw CloudHttp.failure(response, label() + " connection status");
        }
        try {
            return Json.parse(response.body());
        } catch (IllegalArgumentException ex) {
            throw new VpnOperationException("VPN_PROVIDER_ERROR",
                    label() + " returned a response that could not be parsed as JSON.", false, ex);
        }
    }

    @Override
    public Set<String> supportedOperations() {
        return Set.of("CONNECT", "STATUS", "TEST_CONNECTION", "GET_INFO", "WAIT_UNTIL_CONNECTED");
    }
}
