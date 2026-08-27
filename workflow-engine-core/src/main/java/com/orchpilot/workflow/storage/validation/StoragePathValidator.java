package com.orchpilot.workflow.storage.validation;

import com.orchpilot.workflow.storage.dto.PathProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a candidate storage root is real, reachable and writable — by trying it, not by guessing.
 *
 * <h2>Why it actually writes a file</h2>
 *
 * {@link Files#isWritable} consults permission bits, and on Windows and on network shares it is routinely wrong:
 * it returns true for a read-only share and false for a directory the process can write through an ACL. A
 * configuration that passes validation and then fails on the first upload is worse than one that fails now, so
 * the probe performs the whole cycle — create a uniquely-named temporary file, write bytes, read them back,
 * compare, delete — and reports what happened.
 *
 * <h2>Canonicalisation is the security-relevant part</h2>
 *
 * The path is resolved through {@link Path#toRealPath} so the value stored in the settings document has no
 * symlinks, {@code .} or {@code ..} left in it. Every later containment check compares against that resolved
 * value. Skipping this would mean a root of {@code /data/link} pointing at {@code /} would pass a naive prefix
 * check for any file on the machine.
 */
@Component
public class StoragePathValidator {

    private static final Logger log = LoggerFactory.getLogger(StoragePathValidator.class);

    private static final String PROBE_PREFIX = ".orchpilot-storage-probe-";
    private static final byte[] PROBE_CONTENT =
            "OrchPilot storage probe".getBytes(StandardCharsets.UTF_8);

    /**
     * Validates a candidate root, optionally creating it.
     *
     * @param rawPath        the administrator's input
     * @param createIfMissing create the directory (and parents) when it does not exist
     * @return what was found; never throws for an unusable path, because "unusable" is the answer the caller wants
     */
    public PathProbeResult probe(String rawPath, boolean createIfMissing) {
        List<String> problems = new ArrayList<>();

        Path candidate;
        try {
            candidate = parseAbsolute(rawPath);
        } catch (IllegalArgumentException ex) {
            return PathProbeResult.invalid(rawPath, List.of(ex.getMessage()));
        }

        boolean created = false;
        if (!Files.exists(candidate)) {
            if (!createIfMissing) {
                return PathProbeResult.invalid(rawPath,
                        List.of("The directory does not exist. Tick 'create it if missing' to have OrchPilot "
                                + "create it, or create it yourself and test again."));
            }
            try {
                Files.createDirectories(candidate);
                created = true;
            } catch (IOException ex) {
                log.warn("Could not create storage directory {}: {}", candidate, ex.toString());
                return PathProbeResult.invalid(rawPath,
                        List.of("The directory does not exist and could not be created. Check that the parent "
                                + "directory exists and that the OrchPilot process may write to it."));
            }
        }

        if (!Files.isDirectory(candidate)) {
            return PathProbeResult.invalid(rawPath, List.of("That path exists but is a file, not a directory."));
        }

        // Resolve only after existence is confirmed: toRealPath requires the path to exist.
        Path canonical;
        try {
            canonical = candidate.toRealPath();
        } catch (IOException ex) {
            log.warn("Could not canonicalise storage directory {}: {}", candidate, ex.toString());
            return PathProbeResult.invalid(rawPath,
                    List.of("The directory could not be resolved. If it is a symbolic link or a network mount, "
                            + "check that the target is reachable."));
        }

        boolean readable = Files.isReadable(canonical);
        if (!readable) {
            problems.add("The directory is not readable by the OrchPilot process.");
        }

        // The real test. Permission bits are advisory; a completed write/read/delete cycle is not.
        boolean writable = probeWriteCycle(canonical, problems);

        long freeSpace = freeSpace(canonical);
        boolean valid = problems.isEmpty();
        return new PathProbeResult(valid, canonical.toString(), readable, writable, created, freeSpace, problems);
    }

    /**
     * Parses the input and requires it to be absolute.
     *
     * <p>A relative root would resolve against the process's working directory, which differs between a
     * development run, a service, and a container — the same configuration would then mean three different
     * locations. Rejecting it is the only way to make the setting mean one thing.
     */
    public Path parseAbsolute(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Enter a storage path.");
        }
        String trimmed = rawPath.trim();
        if (trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The path contains an invalid character.");
        }
        Path path;
        try {
            path = Paths.get(trimmed).normalize();
        } catch (InvalidPathException ex) {
            // The JDK message names the offending character and index, which is genuinely the useful part.
            throw new IllegalArgumentException("That is not a valid path for this operating system: "
                    + ex.getReason() + ".");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Enter an absolute path, such as D:\\OrchPilot\\data or /opt/orchpilot/data. A relative "
                            + "path would depend on where the process happens to be started from.");
        }
        return path;
    }

    /**
     * Creates, writes, reads back, verifies and deletes a probe file.
     *
     * @return whether the full cycle succeeded
     */
    private boolean probeWriteCycle(Path directory, List<String> problems) {
        Path probe = null;
        try {
            probe = Files.createTempFile(directory, PROBE_PREFIX, ".tmp");
            Files.write(probe, PROBE_CONTENT);

            byte[] readBack = Files.readAllBytes(probe);
            if (!java.util.Arrays.equals(PROBE_CONTENT, readBack)) {
                // Seen on misbehaving network mounts: the write is accepted and the read returns something else.
                problems.add("A test file was written but read back with different contents. The location may "
                        + "be an unreliable network mount.");
                return false;
            }
            return true;
        } catch (IOException | SecurityException ex) {
            log.warn("Storage write probe failed in {}: {}", directory, ex.toString());
            problems.add("A test file could not be created in the directory. The OrchPilot process needs write "
                    + "permission on it.");
            return false;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ex) {
                    // Leaving a probe file behind is untidy but harmless; failing the validation over it is not.
                    log.warn("Could not remove storage probe file: {}", ex.toString());
                }
            }
        }
    }

    private long freeSpace(Path directory) {
        try {
            return Files.getFileStore(directory).getUsableSpace();
        } catch (IOException ex) {
            return -1;
        }
    }
}
