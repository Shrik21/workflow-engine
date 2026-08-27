package com.orchpilot.workflow.plugins.vpn.spi;

import java.util.Locale;

/**
 * The connection states every provider is mapped onto.
 *
 * <h2>One vocabulary, so a workflow does not learn each provider's</h2>
 *
 * AWS reports a tunnel as {@code UP} or {@code DOWN}, Azure as {@code Connected} or {@code Connecting}, GCP as
 * {@code ESTABLISHED}. A decision node that had to know all of those would break the moment a workflow moved
 * from one cloud to another. Each provider maps its own words onto these, and a workflow branches on
 * {@code ${vpnResult.status}} without caring who answered.
 *
 * <h2>UNKNOWN is not FAILED</h2>
 *
 * They are different facts and a workflow acts on them differently. FAILED means the provider said the
 * connection is down or errored. UNKNOWN means this plugin could not determine the state — a status field it
 * did not recognise, a control-plane call that a restricted credential was not allowed to make. Collapsing the
 * two would turn "I could not check" into "it is broken", and a workflow would tear down a healthy connection
 * on the strength of a missing IAM permission.
 */
public enum VpnStatus {

    /** No tunnel, and none being brought up. */
    DISCONNECTED,

    /** A tunnel is being negotiated; not yet usable. */
    CONNECTING,

    /** Up, according to the provider. */
    CONNECTED,

    /** Being torn down. */
    DISCONNECTING,

    /** The provider reports the connection down or in error. */
    FAILED,

    /** The state could not be determined. Distinct from FAILED; see the class note. */
    UNKNOWN,

    /** A wait for CONNECTED ran out of time. Set by the node, never by a provider. */
    TIMEOUT;

    /** @return whether this is a settled, non-transitional state a workflow can act on */
    public boolean isTerminal() {
        return this == CONNECTED || this == FAILED || this == DISCONNECTED || this == TIMEOUT;
    }

    /** @return whether a wait-until-connected loop should keep polling */
    public boolean isInFlight() {
        return this == CONNECTING || this == DISCONNECTING || this == UNKNOWN;
    }

    /**
     * @param value a provider or configured name, in any case
     * @return the matching status, or {@link #UNKNOWN} — never a guess, because misreading a state is how a
     *         workflow acts on a connection that is not in the state it thinks
     */
    public static VpnStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (VpnStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return UNKNOWN;
    }
}
