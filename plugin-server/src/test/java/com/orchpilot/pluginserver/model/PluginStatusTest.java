package com.orchpilot.pluginserver.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle rules, which decide whether a running workflow keeps working.
 */
class PluginStatusTest {

    @Test
    @DisplayName("deprecated still downloads, revoked does not")
    void distributionFollowsIntent() {
        // A workflow pinned to a deprecated version must keep running, so the bytes stay available.
        assertTrue(PluginStatus.DEPRECATED.isDistributable());
        assertTrue(PluginStatus.INACTIVE.isDistributable(),
                "withdrawn from the catalogue is not the same as withdrawn from existence");
        assertTrue(PluginStatus.ACTIVE.isDistributable());

        // Revoking is the one state that says "this must not run anywhere".
        assertFalse(PluginStatus.REVOKED.isDistributable());
        assertFalse(PluginStatus.DRAFT.isDistributable(), "nothing should be running an unpublished draft");
    }

    @Test
    @DisplayName("only an active release can be a plugin's latest version")
    void onlyActiveIsLatest() {
        assertTrue(PluginStatus.ACTIVE.isSelectableAsLatest());
        for (PluginStatus other : new PluginStatus[]{PluginStatus.DRAFT, PluginStatus.INACTIVE,
                PluginStatus.DEPRECATED, PluginStatus.REVOKED}) {
            assertFalse(other.isSelectableAsLatest(), other + " must not become latest");
        }
    }

    @Test
    @DisplayName("a revocation is final")
    void revocationIsFinal() {
        // Otherwise a version withdrawn because it leaked credentials could be reinstated by a second call.
        for (PluginStatus target : PluginStatus.values()) {
            assertFalse(PluginStatus.REVOKED.canTransitionTo(target),
                    "REVOKED must not become " + target);
        }
    }

    @Test
    @DisplayName("nothing transitions to itself, or back to draft")
    void noPointlessTransitions() {
        for (PluginStatus status : PluginStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " to itself should be refused");
            assertFalse(status.canTransitionTo(PluginStatus.DRAFT),
                    "DRAFT is where a version starts, not somewhere it returns to");
            assertFalse(status.canTransitionTo(null));
        }
    }

    @Test
    @DisplayName("the ordinary route through the lifecycle is allowed")
    void ordinaryRouteIsAllowed() {
        assertTrue(PluginStatus.DRAFT.canTransitionTo(PluginStatus.ACTIVE));
        assertTrue(PluginStatus.ACTIVE.canTransitionTo(PluginStatus.DEPRECATED));
        assertTrue(PluginStatus.ACTIVE.canTransitionTo(PluginStatus.INACTIVE));
        assertTrue(PluginStatus.INACTIVE.canTransitionTo(PluginStatus.ACTIVE));
        assertTrue(PluginStatus.DEPRECATED.canTransitionTo(PluginStatus.REVOKED));
    }

    @Test
    @DisplayName("version precedence is stored in fields a database can sort on")
    void versionOrderIsSortable() {
        VersionOrder release = VersionOrder.of("1.10.0");
        VersionOrder preRelease = VersionOrder.of("1.10.0-rc.1");

        assertEquals(1, release.major());
        assertEquals(10, release.minor());
        assertEquals(0, release.patch());
        assertEquals(1, release.releaseRank(), "a release outranks its own pre-release");
        assertEquals(0, preRelease.releaseRank());
        assertTrue(preRelease.isPreRelease());
        assertFalse(release.isPreRelease());

        // An unparseable version has no order, which keeps it out of every latest-version query.
        assertNull(VersionOrder.of("latest"));
        assertNull(VersionOrder.of(null));
    }
}
