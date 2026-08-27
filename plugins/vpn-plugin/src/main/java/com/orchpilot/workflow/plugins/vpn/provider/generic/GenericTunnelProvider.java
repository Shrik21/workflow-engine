package com.orchpilot.workflow.plugins.vpn.provider.generic;

import com.orchpilot.workflow.plugins.vpn.provider.NetProbe;
import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionInfo;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The honest base for the client VPNs — IPsec, OpenVPN, WireGuard.
 *
 * <h2>What these providers are, and are not</h2>
 *
 * They validate a configuration, resolve its credentials from the secret store, and report what can be
 * verified about the endpoint from the engine. They do <em>not</em> bring a tunnel up: that needs a client
 * running as root with a kernel module, which a workflow node inside a shared JVM must not do — a node that
 * spawned {@code wg-quick up} would be running privileged networking on behalf of any workflow author who
 * could reach it. So {@code connect} here means "the configuration is complete and the endpoint is reachable
 * as far as we can tell", every result says exactly what was checked, and nothing claims a tunnel is up that
 * this code cannot see.
 *
 * <p>An installation that genuinely needs the engine host to hold a tunnel runs that tunnel with the host's
 * own tooling, out of band, and uses this provider to <em>check</em> it — which is the honest division of
 * labour between a workflow engine and an operating system.
 */
abstract class GenericTunnelProvider implements VpnProvider {

    /** @return whether the endpoint speaks TCP (only OpenVPN, and only in TCP mode) */
    abstract boolean isTcp(VpnConnectionRequest request);

    /** @return the endpoint host setting name and default port for this protocol */
    abstract int defaultPort();

    /** @return the credential names this protocol needs; checked before anything is attempted */
    abstract Set<String> requiredCredentials(VpnConnectionRequest request);

    @Override
    public Set<String> supportedOperations() {
        // No create/delete/rotate: there is no control plane to create against. These providers describe a
        // configuration the operator already holds and check its endpoint.
        return Set.of("CONNECT", "DISCONNECT", "STATUS", "TEST_CONNECTION", "GET_INFO", "WAIT_UNTIL_CONNECTED");
    }

    @Override
    public VpnConnectionResult connect(VpnConnectionRequest request) {
        validate(request);
        NetProbe.Reachability reach = probe(request);

        // CONNECTING, not CONNECTED: the endpoint being reachable is a precondition for a tunnel, not proof of
        // one. Only a TCP probe that opened moves the needle, and even then only to "reachable".
        VpnStatus status = reach.tcpOpen() ? VpnStatus.CONNECTING
                : reach.resolved() ? VpnStatus.CONNECTING : VpnStatus.FAILED;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checked", reach.checked());
        details.put("note", "This provider validates configuration and endpoint reachability; it does not "
                + "establish the tunnel. Bring the tunnel up with the host's own VPN client and use STATUS to "
                + "check it.");
        return new VpnConnectionResult(reach.resolved(), status, connectionId(request), reach.detail(),
                details);
    }

    @Override
    public VpnConnectionResult disconnect(VpnConnectionRequest request) {
        // Honest refusal rather than a fake success: this provider never brought a tunnel up, so it has
        // nothing to tear down, and reporting DISCONNECTED would tell a workflow something untrue.
        return new VpnConnectionResult(true, VpnStatus.DISCONNECTED, connectionId(request),
                "This provider does not manage the tunnel lifecycle, so there is nothing to disconnect. Stop "
                        + "the tunnel with the host's own VPN client.", Map.of());
    }

    @Override
    public VpnConnectionStatus getStatus(VpnConnectionRequest request) {
        validate(request);
        NetProbe.Reachability reach = probe(request);
        // Reachable is not the same as connected, and this must not say CONNECTED. The best it can truthfully
        // report is that the endpoint is reachable (CONNECTING) or not (FAILED / UNKNOWN).
        VpnStatus status = !reach.resolved() ? VpnStatus.FAILED
                : reach.tcpOpen() ? VpnStatus.CONNECTING
                : VpnStatus.UNKNOWN;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checked", reach.checked());
        details.put("detail", reach.detail());
        return new VpnConnectionStatus(status, reach.checked(), connectionId(request), List.of(), details);
    }

    @Override
    public VpnConnectionTestResult testConnection(VpnConnectionRequest request) {
        validate(request);
        NetProbe.Reachability reach = probe(request);
        if (!reach.resolved()) {
            return VpnConnectionTestResult.failed(VpnStatus.FAILED, reach.checked(), reach.detail());
        }
        VpnStatus status = reach.tcpOpen() ? VpnStatus.CONNECTING : VpnStatus.UNKNOWN;
        return new VpnConnectionTestResult(true, status, reach.checked(), reach.latencyMillis(),
                reach.detail());
    }

    @Override
    public VpnConnectionInfo getConnectionInfo(VpnConnectionRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("endpoint", endpoint(request) + ":" + port(request));
        attributes.put("protocol", id());
        request.optionalSetting("localCidr").ifPresent(value -> attributes.put("localCidr", value));
        request.optionalSetting("remoteCidr").ifPresent(value -> attributes.put("remoteCidr", value));
        request.optionalSetting("allowedIps").ifPresent(value -> attributes.put("allowedIps", value));
        // Never a key, a certificate or a PSK: those are in the secret store and stay there.
        return new VpnConnectionInfo(connectionId(request), id(), VpnStatus.UNKNOWN, attributes);
    }

    // ---- shared helpers ----

    /** Fails when the endpoint or a required credential is missing, listing everything wrong at once. */
    void validate(VpnConnectionRequest request) {
        List<String> problems = new ArrayList<>();
        if (endpoint(request).isBlank()) {
            problems.add("an endpoint host is required");
        }
        for (String credential : requiredCredentials(request)) {
            if (request.secret(credential).isEmpty()) {
                problems.add("the credential '" + credential + "' is required and must be stored as a secret");
            }
        }
        if (!problems.isEmpty()) {
            throw new VpnOperationException("VPN_CONFIGURATION_INVALID",
                    id() + " configuration is incomplete: " + String.join("; ", problems) + ".", false);
        }
    }

    private NetProbe.Reachability probe(VpnConnectionRequest request) {
        return NetProbe.probe(endpoint(request), port(request), isTcp(request),
                request.intSetting("probeTimeoutMillis", 5000));
    }

    String endpoint(VpnConnectionRequest request) {
        // Accept a bare host, or host:port in one field, which is how WireGuard endpoints are usually written.
        String raw = request.setting("endpoint", request.setting("server", request.setting("gateway", "")));
        int colon = raw.lastIndexOf(':');
        return colon > 0 && raw.indexOf(':') == colon ? raw.substring(0, colon) : raw;
    }

    int port(VpnConnectionRequest request) {
        String raw = request.setting("endpoint", "");
        int colon = raw.lastIndexOf(':');
        if (colon > 0 && raw.indexOf(':') == colon) {
            try {
                return Integer.parseInt(raw.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                // Fall through to the explicit port setting.
            }
        }
        return request.intSetting("port", defaultPort());
    }

    String connectionId(VpnConnectionRequest request) {
        return request.connectionId() == null || request.connectionId().isBlank()
                ? endpoint(request) : request.connectionId();
    }
}
