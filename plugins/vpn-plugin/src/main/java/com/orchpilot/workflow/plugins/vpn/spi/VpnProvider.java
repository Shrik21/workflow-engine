package com.orchpilot.workflow.plugins.vpn.spi;

import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionInfo;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionResult;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionStatus;
import com.orchpilot.workflow.plugins.vpn.spi.VpnResults.VpnConnectionTestResult;

import java.util.Set;

/**
 * One VPN provider.
 *
 * <h2>The whole point of the plugin</h2>
 *
 * A new provider is a new class implementing this interface and a line registering it — no change to the
 * workflow engine, no change to the node, no new node type. The engine never sees this interface; it sees the
 * one {@code VPN} node, and the node dispatches here. That is the "additional providers without modifying the
 * engine" requirement, and it is the reason the SPI is a small, closed vocabulary of {@link VpnStatus},
 * {@link VpnConnectionRequest} and the result records rather than each provider's own types.
 *
 * <h2>What a provider must not do</h2>
 *
 * <ul>
 *   <li><b>Invent a connect that the provider does not have.</b> A cloud Site-to-Site VPN is always-on
 *       infrastructure with no dial button; its tunnel comes up when the peer negotiates IKE. For such a
 *       provider {@code connect} converges and reports state, and says so — it does not pretend to have
 *       dialed.</li>
 *   <li><b>Claim connectivity it did not verify.</b> A {@link VpnConnectionTestResult} states what it checked.
 *       A control-plane status check must not be reported as a data-plane reachability test.</li>
 *   <li><b>Log, return or embed a secret.</b> Credentials arrive resolved in {@link VpnConnectionRequest} and
 *       must stay there. Every message a provider produces is read by whoever can read the execution.</li>
 * </ul>
 *
 * <p>Implementations are stateless and thread-safe: one instance serves every execution, concurrently.
 */
public interface VpnProvider {

    /** @return the provider id, matching what a node's {@code provider} field holds, e.g. {@code AWS} */
    String id();

    /** @return a human label for the designer's dropdown */
    String label();

    /**
     * @return the operations this provider actually supports, so the node can refuse the rest with a clear
     *         message rather than a provider error several layers down — the spec's "only expose operations
     *         supported by the API"
     */
    Set<String> supportedOperations();

    /**
     * Brings the connection into being or up to date, and reports its state.
     *
     * <p>For a cloud provider with no dial operation this ensures the connection exists and reports its tunnel
     * state; the result's message says which. For a provider that genuinely establishes a session it does so.
     *
     * @param request the resolved request
     * @return the outcome
     */
    VpnConnectionResult connect(VpnConnectionRequest request);

    /**
     * Tears the connection down, where the provider supports it.
     *
     * <p>Many cloud connections cannot be "disconnected" without deleting them; a provider that cannot honour
     * this returns a result saying so rather than a false success.
     *
     * @param request the resolved request
     * @return the outcome
     */
    VpnConnectionResult disconnect(VpnConnectionRequest request);

    /**
     * Reads the current state.
     *
     * @param request the resolved request
     * @return the status
     */
    VpnConnectionStatus getStatus(VpnConnectionRequest request);

    /**
     * Tests the connection, reporting exactly what was tested.
     *
     * @param request the resolved request
     * @return the test result
     */
    VpnConnectionTestResult testConnection(VpnConnectionRequest request);

    /**
     * Reads descriptive information: addresses, gateways, routes. Never a credential.
     *
     * @param request the resolved request
     * @return the information
     */
    VpnConnectionInfo getConnectionInfo(VpnConnectionRequest request);

    /**
     * The credential names this provider reads, so the node can resolve them from the secret store and the
     * documentation can list them. Names only — never values.
     *
     * @return the logical credential names, e.g. {@code accessKeyId}, {@code secretKey}
     */
    default Set<String> credentialNames() {
        return Set.of();
    }
}
