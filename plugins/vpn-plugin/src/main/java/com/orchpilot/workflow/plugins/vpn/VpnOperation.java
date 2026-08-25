package com.orchpilot.workflow.plugins.vpn;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a VPN node does.
 *
 * <p>One node with an operation selector, not ten node types — the same choice the MongoDB plugin made and for
 * the same reason: the operations share a connection, a provider and a credential, and splitting them into
 * separate palette entries would multiply what an operator has to wire without separating anything that
 * matters. Which operations are offered for a given provider is decided by the provider itself
 * ({@link com.orchpilot.workflow.plugins.vpn.spi.VpnProvider#supportedOperations()}), because a cloud gateway and
 * a WireGuard peer genuinely do different things.
 */
enum VpnOperation {

    /** Ensure the connection is up / converged, and report its state. */
    CONNECT,

    /** Tear it down, where the provider supports it. */
    DISCONNECT,

    /** Read the current state. */
    STATUS,

    /** Test it, reporting what was tested. */
    TEST_CONNECTION,

    /** Read descriptive information. */
    GET_INFO,

    /** Poll STATUS until CONNECTED or a timeout. */
    WAIT_UNTIL_CONNECTED,

    // ---- advanced, where the provider's API supports them ----
    CREATE,
    DELETE,
    ROTATE_CREDENTIALS,
    UPDATE_CONFIG;

    static Optional<VpnOperation> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(operation -> operation.name().equals(name)).findFirst();
    }

    static Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (VpnOperation operation : values()) {
            names.add(operation.name());
        }
        return names;
    }
}
