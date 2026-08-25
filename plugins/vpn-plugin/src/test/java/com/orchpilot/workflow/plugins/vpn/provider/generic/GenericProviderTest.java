package com.orchpilot.workflow.plugins.vpn.provider.generic;

import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic providers, and the line they will not cross: they never claim a tunnel is up.
 *
 * <p>OpenVPN-over-TCP can be genuinely probed, so that path is tested against a real local socket. The UDP
 * protocols cannot be, so those are tested to confirm they report exactly that rather than inventing a state.
 */
class GenericProviderTest {

    private static VpnConnectionRequest request(String provider, Map<String, Object> settings,
                                                Map<String, String> secrets) {
        return new VpnConnectionRequest("TEST_CONNECTION", provider, null, "", settings, secrets);
    }

    @Test
    @DisplayName("OpenVPN over TCP opens a real socket, and reports reachable — not connected")
    void openVpnTcpReachable() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            VpnConnectionTestResult result = new OpenVpnProvider().testConnection(request("OPENVPN",
                    Map.of("endpoint", "127.0.0.1", "port", server.getLocalPort(), "protocol", "TCP"),
                    Map.of()));

            assertTrue(result.success());
            // Reachable is CONNECTING at most: a socket opening does not prove the tunnel is up, and the
            // result must not say CONNECTED.
            assertEquals(VpnStatus.CONNECTING, result.status());
            assertNotNull(result.latencyMillis());
            assertTrue(result.checked().contains("TCP connect"));
        }
    }

    @Test
    @DisplayName("OpenVPN over TCP to a closed port reports the port unreachable, honestly")
    void openVpnTcpUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        VpnConnectionTestResult result = new OpenVpnProvider().testConnection(request("OPENVPN",
                Map.of("endpoint", "127.0.0.1", "port", closedPort, "protocol", "TCP"), Map.of()));

        assertEquals(VpnStatus.UNKNOWN, result.status());
        assertFalse(result.checked().isBlank());
    }

    @Test
    @DisplayName("WireGuard says plainly that the tunnel state is not observable")
    void wireGuardUdpHonest() {
        VpnConnectionTestResult result = new WireGuardProvider().testConnection(request("WIREGUARD",
                Map.of("endpoint", "127.0.0.1:51820"), Map.of("privateKey", "a-key")));

        // A UDP endpoint cannot be probed; the result must not claim CONNECTED and must say what it checked.
        assertFalse(result.status() == VpnStatus.CONNECTED);
        assertTrue(result.checked().toLowerCase().contains("udp")
                || result.message().toLowerCase().contains("not observable")
                || result.checked().toLowerCase().contains("dns"));
    }

    @Test
    @DisplayName("IPsec requires its pre-shared key, from a secret")
    void ipsecRequiresPsk() {
        VpnOperationException failure = assertThrows(VpnOperationException.class,
                () -> new IpsecVpnProvider().testConnection(request("IPSEC",
                        Map.of("endpoint", "gw.example.com", "authentication", "PSK"), Map.of())));
        assertEquals("VPN_CONFIGURATION_INVALID", failure.code());
        assertTrue(failure.getMessage().contains("presharedKey"));
    }

    @Test
    @DisplayName("WireGuard requires its private key")
    void wireGuardRequiresPrivateKey() {
        VpnOperationException failure = assertThrows(VpnOperationException.class,
                () -> new WireGuardProvider().testConnection(request("WIREGUARD",
                        Map.of("endpoint", "vpn.example.com:51820"), Map.of())));
        assertEquals("VPN_CONFIGURATION_INVALID", failure.code());
    }

    @Test
    @DisplayName("disconnect is an honest no-op, not a fake success")
    void disconnectHonest() {
        var result = new WireGuardProvider().disconnect(request("WIREGUARD",
                Map.of("endpoint", "vpn.example.com:51820"), Map.of("privateKey", "a-key")));
        assertTrue(result.message().toLowerCase().contains("does not manage the tunnel"));
    }

    @Test
    @DisplayName("connection info never contains a credential")
    void infoHasNoSecret() {
        var info = new WireGuardProvider().getConnectionInfo(request("WIREGUARD",
                Map.of("endpoint", "vpn.example.com:51820", "allowedIps", "10.0.0.0/8"),
                Map.of("privateKey", "super-secret-key")));

        assertFalse(info.attributes().toString().contains("super-secret-key"));
        assertEquals("vpn.example.com:51820", info.attributes().get("endpoint"));
    }
}
