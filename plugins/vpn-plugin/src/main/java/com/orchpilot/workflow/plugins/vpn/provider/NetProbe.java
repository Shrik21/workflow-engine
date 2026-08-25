package com.orchpilot.workflow.plugins.vpn.provider;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * The honest checks a generic provider can perform from the engine, and their exact meaning.
 *
 * <h2>What can and cannot be verified from here</h2>
 *
 * A generic VPN — OpenVPN, IPsec, WireGuard — brings a tunnel up with a client that needs root, a kernel
 * module and, for IPsec and WireGuard, UDP the engine may not be able to send. This plugin does not run those
 * clients, so it cannot observe the tunnel. What it <em>can</em> do is bounded and each result says which:
 *
 * <ul>
 *   <li><b>Resolve the endpoint.</b> That the gateway's name resolves is a real, if small, fact.</li>
 *   <li><b>Open a TCP socket to it.</b> Meaningful only for a TCP-based endpoint — OpenVPN over TCP. A
 *       successful connect proves the port is reachable and accepting, which is genuinely useful before a
 *       tunnel is attempted. It does <em>not</em> prove the tunnel is up.</li>
 * </ul>
 *
 * <p>UDP endpoints (IPsec/500, WireGuard) cannot be probed this way: a UDP "connect" sends nothing and proves
 * nothing, so this class refuses to pretend and the provider reports that the tunnel state is not observable.
 * That is the spec's "do not falsely report network connectivity" turned into a method that will not lie.
 */
public final class NetProbe {

    private NetProbe() {
    }

    /** What a probe found, and what it therefore means. */
    public record Reachability(boolean resolved, boolean tcpOpen, Long latencyMillis, String checked, String detail) {
    }

    /**
     * Resolves a host and, for a TCP endpoint, opens a socket to it.
     *
     * @param host      the gateway host
     * @param port      the port
     * @param tcp       whether the endpoint is TCP; false skips the socket and says the tunnel is unobservable
     * @param timeoutMs how long to wait for the socket
     * @return what was found
     */
    public static Reachability probe(String host, int port, boolean tcp, int timeoutMs) {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException ex) {
            return new Reachability(false, false, null,
                    "DNS resolution of " + host,
                    "The gateway host '" + host + "' does not resolve.");
        }

        if (!tcp) {
            return new Reachability(true, false, null,
                    "DNS resolution of " + host + " (endpoint is UDP; the tunnel state is not observable "
                            + "from the engine)",
                    "'" + host + "' resolves to " + address.getHostAddress() + ". This is a control-plane "
                            + "check only: a UDP VPN endpoint cannot be probed without bringing the tunnel up, "
                            + "which this plugin does not do.");
        }

        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            long latency = (System.nanoTime() - started) / 1_000_000;
            return new Reachability(true, true, latency,
                    "TCP connect to " + host + ":" + port,
                    "Opened a TCP connection to " + host + ":" + port + " in " + latency + " ms. The port is "
                            + "reachable and accepting; this does not by itself prove the VPN tunnel is up.");
        } catch (java.io.IOException ex) {
            return new Reachability(true, false, null,
                    "TCP connect to " + host + ":" + port,
                    "'" + host + "' resolves, but a TCP connection to port " + port + " could not be opened: "
                            + ex.getClass().getSimpleName() + ".");
        }
    }
}
