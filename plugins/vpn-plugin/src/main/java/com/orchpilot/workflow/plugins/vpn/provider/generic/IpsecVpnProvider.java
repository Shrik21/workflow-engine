package com.orchpilot.workflow.plugins.vpn.provider.generic;

import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;

import java.util.Set;

/**
 * Standard IPsec (IKEv1 / IKEv2) configurations.
 *
 * <p>IPsec negotiates over UDP 500 (and 4500 for NAT-T), so there is no TCP port to probe and the base
 * provider's honest UDP path applies: it validates the configuration — remote gateway, IKE version, proposals,
 * CIDRs — resolves the pre-shared key from the secret store, and reports that the tunnel state is not
 * observable from the engine. The pre-shared key is a credential name, never a value in the workflow.
 */
public final class IpsecVpnProvider extends GenericTunnelProvider {

    @Override
    public String id() {
        return "IPSEC";
    }

    @Override
    public String label() {
        return "Generic IPsec";
    }

    @Override
    boolean isTcp(VpnConnectionRequest request) {
        return false;
    }

    @Override
    int defaultPort() {
        return 500;
    }

    @Override
    Set<String> requiredCredentials(VpnConnectionRequest request) {
        // A PSK tunnel needs the PSK; a certificate tunnel does not. Require the PSK only when the operator
        // said the authentication is PSK, which is the default.
        boolean psk = !"CERT".equalsIgnoreCase(request.setting("authentication", "PSK"));
        return psk ? Set.of("presharedKey") : Set.of();
    }

    @Override
    public Set<String> credentialNames() {
        return Set.of("presharedKey", "certificate", "privateKey");
    }
}
