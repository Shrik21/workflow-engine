package com.orchpilot.workflow.plugins.vpn.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a provider returns from each SPI method.
 *
 * <h2>Every result says what was checked</h2>
 *
 * A VPN result that claims CONNECTED without saying how it knows is worse than useless, because a workflow
 * will deploy to an environment it cannot reach. So each result carries a {@code checked} field — "read the
 * AWS tunnel telemetry", "opened a TCP socket to the server", "resolved the endpoint and validated the
 * configuration; the tunnel itself is not observable from here" — and the node surfaces it. This is the
 * spec's "do not falsely report network connectivity if only control-plane status was checked", made a field
 * rather than a hope.
 */
public final class VpnResults {

    private VpnResults() {
    }

    /**
     * The outcome of connect, disconnect, create, delete and the other state-changing operations.
     *
     * @param success      whether the operation did what it was asked
     * @param status       the resulting standard status
     * @param connectionId the connection acted on
     * @param message      a sentence for the execution record
     * @param details      provider-specific facts, none of them secret
     */
    public record VpnConnectionResult(boolean success, VpnStatus status, String connectionId,
                                      String message, Map<String, Object> details) {

        public VpnConnectionResult {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static VpnConnectionResult ok(VpnStatus status, String connectionId, String message) {
            return new VpnConnectionResult(true, status, connectionId, message, Map.of());
        }

        public static VpnConnectionResult ok(VpnStatus status, String connectionId, String message,
                                             Map<String, Object> details) {
            return new VpnConnectionResult(true, status, connectionId, message, details);
        }

        public static VpnConnectionResult failed(VpnStatus status, String connectionId, String message) {
            return new VpnConnectionResult(false, status, connectionId, message, Map.of());
        }
    }

    /**
     * The current state of a connection.
     *
     * @param status       the standard status
     * @param providerState the provider's own state string, kept because it is what an operator sees in the
     *                     provider console and the only way to reconcile the two
     * @param connectionId the connection
     * @param tunnels      per-tunnel state, where the provider exposes more than one
     * @param details      provider-specific facts
     */
    public record VpnConnectionStatus(VpnStatus status, String providerState, String connectionId,
                                      List<TunnelState> tunnels, Map<String, Object> details) {

        public VpnConnectionStatus {
            tunnels = tunnels == null ? List.of() : List.copyOf(tunnels);
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static VpnConnectionStatus of(VpnStatus status, String providerState, String connectionId) {
            return new VpnConnectionStatus(status, providerState, connectionId, List.of(), Map.of());
        }
    }

    /** One tunnel of a connection that has several, such as AWS's two. */
    public record TunnelState(String address, VpnStatus status, String providerState) {
    }

    /**
     * The outcome of a test.
     *
     * @param success   whether the test passed
     * @param status    the status it observed
     * @param checked   exactly what was tested, in words — the honesty field
     * @param latencyMillis round-trip latency where a real probe measured one, or {@code null}
     * @param message   a sentence for the execution record
     */
    public record VpnConnectionTestResult(boolean success, VpnStatus status, String checked,
                                          Long latencyMillis, String message) {

        public static VpnConnectionTestResult passed(VpnStatus status, String checked, Long latencyMillis,
                                                     String message) {
            return new VpnConnectionTestResult(true, status, checked, latencyMillis, message);
        }

        public static VpnConnectionTestResult failed(VpnStatus status, String checked, String message) {
            return new VpnConnectionTestResult(false, status, checked, null, message);
        }
    }

    /**
     * Descriptive information about a connection: addresses, gateways, routes. Never a credential.
     *
     * @param connectionId the connection
     * @param provider     the provider
     * @param status       its current standard status
     * @param attributes   the descriptive facts
     */
    public record VpnConnectionInfo(String connectionId, String provider, VpnStatus status,
                                    Map<String, Object> attributes) {

        public VpnConnectionInfo {
            attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
        }
    }
}
