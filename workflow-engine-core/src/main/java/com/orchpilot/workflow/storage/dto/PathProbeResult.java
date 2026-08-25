package com.orchpilot.workflow.storage.dto;

import java.util.List;

/**
 * What testing a candidate storage path found.
 *
 * @param valid          whether the path is usable as a storage root
 * @param canonicalPath  the resolved absolute path, with symlinks and {@code ..} removed
 * @param readable       the directory could be read
 * @param writable       a probe file was created, written, read back and deleted
 * @param created        the directory did not exist and was created by this probe
 * @param freeSpaceBytes usable space on the containing volume, or -1 when it could not be determined
 * @param problems       human-readable reasons it is unusable; empty when {@code valid}
 */
public record PathProbeResult(boolean valid, String canonicalPath, boolean readable, boolean writable,
                              boolean created, long freeSpaceBytes, List<String> problems) {

    public PathProbeResult {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /**
     * A failed probe.
     *
     * <p>Echoes the path the administrator typed rather than a resolved one — there is nothing to resolve when
     * the path is unusable, and showing the input back is what lets them spot a typo.
     */
    public static PathProbeResult invalid(String rawPath, List<String> problems) {
        return new PathProbeResult(false, rawPath, false, false, false, -1, problems);
    }
}
