package com.orchpilot.workflow.plugins.vpn.provider.generic;

import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;

import java.util.Set;

/**
 * WireGuard peer configurations.
 *
 * <p>WireGuard is UDP and, by design, silent until a valid, authenticated packet arrives — there is nothing to
 * probe and no handshake to observe from outside. So this validates the peer configuration (endpoint, allowed
 * IPs, keepalive), resolves the private key from the secret store, and reports honestly that the tunnel state
 * is not observable from the engine. The private key is a credential name; it never appears in configuration,
 * a log or a result, as the spec requires.
 */
public final class WireGuardProvider extends GenericTunnelProvider {

    @Override
    public String id() {
        return "WIREGUARD";
    }

    @Override
    public String label() {
        return "WireGuard";
    }

    @Override
    boolean isTcp(VpnConnectionRequest request) {
        return false;
    }

    @Override
    int defaultPort() {
        return 51820;
    }

    @Override
    Set<String> requiredCredentials(VpnConnectionRequest request) {
        return Set.of("privateKey");
    }

    @Override
    public Set<String> credentialNames() {
        return Set.of("privateKey", "presharedKey");
    }
}
