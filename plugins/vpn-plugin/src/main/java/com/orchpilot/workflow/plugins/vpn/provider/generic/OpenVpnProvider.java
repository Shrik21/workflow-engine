package com.orchpilot.workflow.plugins.vpn.provider.generic;

import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;

import java.util.Locale;
import java.util.Set;

/**
 * OpenVPN client configurations.
 *
 * <p>The one generic provider whose endpoint can be genuinely probed: OpenVPN runs over TCP or UDP, and in TCP
 * mode a socket to the server port is a real reachability check. In UDP mode it falls back to the honest
 * control-plane check the base provides. Credentials — CA, client certificate, client key, username, password
 * — are read from the secret store by name and never appear in configuration, a log, or a result.
 */
public final class OpenVpnProvider extends GenericTunnelProvider {

    @Override
    public String id() {
        return "OPENVPN";
    }

    @Override
    public String label() {
        return "OpenVPN";
    }

    @Override
    boolean isTcp(VpnConnectionRequest request) {
        return "TCP".equals(request.setting("protocol", "UDP").toUpperCase(Locale.ROOT));
    }

    @Override
    int defaultPort() {
        return 1194;
    }

    @Override
    Set<String> requiredCredentials(VpnConnectionRequest request) {
        // Certificate auth or user/password; require nothing hard here beyond what the operator chose, because
        // OpenVPN supports several combinations and refusing a valid one is worse than a server-side error.
        return Set.of();
    }

    @Override
    public Set<String> credentialNames() {
        return Set.of("caCertificate", "clientCertificate", "clientKey", "username", "password");
    }
}
