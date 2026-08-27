package com.orchpilot.pluginserver.model;

import com.orchpilot.workflow.sdk.version.SemanticVersion;

/**
 * A version's precedence, stored in fields MongoDB can sort on.
 *
 * <p>The version is already on the document as text, so this looks redundant. It is not: sorting
 * {@code "1.10.0"} against {@code "1.9.0"} as a string puts them in the wrong order, and the database cannot
 * call {@link SemanticVersion}. Without these fields, resolving a plugin's latest version would mean loading
 * every version of it into the application and sorting there, which is the sort of thing that works fine until a
 * plugin has two hundred versions.
 *
 * <p>Derived, never authored: {@link #of(String)} is the only way to make one, so it cannot disagree with the
 * text it came from.
 *
 * @param major       major component
 * @param minor       minor component
 * @param patch       patch component
 * @param preRelease  pre-release tag, or null for a release
 * @param releaseRank {@code 1} for a release and {@code 0} for a pre-release, so a single descending sort on
 *                    (major, minor, patch, releaseRank) puts {@code 1.2.0} above {@code 1.2.0-rc.1} without a
 *                    second query or a null-handling special case
 */
public record VersionOrder(int major, int minor, int patch, String preRelease, int releaseRank) {

    /**
     * @param version semantic version text
     * @return its precedence fields, or null when the text is not a semantic version
     */
    public static VersionOrder of(String version) {
        return SemanticVersion.tryParse(version)
                .map(parsed -> new VersionOrder(parsed.major(), parsed.minor(), parsed.patch(),
                        parsed.preRelease().orElse(null), parsed.isPreRelease() ? 0 : 1))
                .orElse(null);
    }

    /** @return whether this is a pre-release, which is never chosen as a plugin's latest version */
    public boolean isPreRelease() {
        return releaseRank == 0;
    }
}
