package com.orchpilot.pluginserver.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle state of a plugin or one of its versions.
 *
 * <p>Deletion is not a state. A registry distributes artefacts that other services are running, so removing one
 * is a separate, forced, audited operation; the ordinary way to withdraw something is to change its state and
 * leave the record behind.
 *
 * <h2>Why DEPRECATED still downloads and REVOKED does not</h2>
 *
 * <p>They express different intentions. Deprecating says "stop choosing this", and a workflow already pinned to
 * it must keep running, so the bytes stay available. Revoking says "this must not run anywhere", which is what
 * you reach for when a version turns out to leak credentials, and refusing the download is the only part of that
 * the registry can enforce.
 */
public enum PluginStatus {

    /** Uploaded and stored, not yet offered to any workflow service. */
    DRAFT,

    /** Published. Appears in the catalogue and can be downloaded. */
    ACTIVE,

    /** Withdrawn from the catalogue, still downloadable by something that already knows the coordinate. */
    INACTIVE,

    /** Superseded. Still downloadable, and flagged so nothing new chooses it. */
    DEPRECATED,

    /** Withdrawn for cause. Downloads are refused. */
    REVOKED;

    private static final Set<PluginStatus> DISTRIBUTABLE =
            EnumSet.of(ACTIVE, INACTIVE, DEPRECATED);

    /** @return whether a workflow service may still fetch these bytes */
    public boolean isDistributable() {
        return DISTRIBUTABLE.contains(this);
    }

    /** @return whether this version appears in the catalogue */
    public boolean isPublished() {
        return this == ACTIVE || this == DEPRECATED;
    }

    /** @return whether this version may be chosen as a plugin's latest */
    public boolean isSelectableAsLatest() {
        return this == ACTIVE;
    }

    /**
     * Whether a transition is allowed.
     *
     * <p>Two rules do the work. A revocation is final, because a version that was withdrawn for cause must not
     * be quietly reinstated by a second API call; publishing a new version is the way forward. And nothing
     * transitions to itself, so a repeated call is reported rather than silently accepted, which matters when the
     * caller is a script that believes it changed something.
     *
     * @param to the target state
     * @return whether this state may become that one
     */
    public boolean canTransitionTo(PluginStatus to) {
        if (to == null || to == this || to == DRAFT) {
            return false;
        }
        return this != REVOKED;
    }
}
