package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.exception.PluginValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Fetches a plugin archive from the registry and proves it is the one the catalogue described.
 *
 * <h2>The checksum is the whole point</h2>
 *
 * <p>Everything downstream of this class treats the bytes as trustworthy enough to load into a class loader. The
 * archive arrives over a network from a service this engine does not control, so the one moment where a substituted
 * or truncated archive can still be caught cheaply is here, before anything reads a class from it. A mismatch is a
 * hard failure with no retry and no partial install.
 *
 * <p>An archive the catalogue publishes <em>no</em> checksum for is refused for the same reason. Absence of a
 * checksum is not a weaker guarantee than a matching one, it is no guarantee at all, and quietly installing anyway
 * would make the check theatre.
 *
 * <h2>Why the bytes are also written to disk</h2>
 *
 * <p>The verified archive is promoted into {@code plugin-cache/<pluginId>/<version>/} on the way past. The engine
 * stores the authoritative copy in GridFS, so the cache is not a source of truth; it is an artefact an operator can
 * look at, checksum by hand, or hand to a support engineer when a plugin misbehaves. It is safe to delete at any
 * time.
 */
@Component
public class PluginArchiveDownloader {

    private static final Logger log = LoggerFactory.getLogger(PluginArchiveDownloader.class);

    private final PluginServerClient client;
    private final PluginServerProperties properties;

    public PluginArchiveDownloader(PluginServerClient client, PluginServerProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * An archive that has been downloaded and proven to match its published checksum.
     *
     * @param pluginId  the plugin
     * @param version   the version
     * @param content   the bytes
     * @param sha256    the verified checksum, lower-case hex
     * @param cachePath where the archive was promoted to, relative to the cache root
     * @param fileName  a file name for the archive
     */
    public record VerifiedArchive(String pluginId, String version, byte[] content, String sha256,
                                  String cachePath, String fileName) {

        /** @return the archive size in bytes */
        public long size() {
            return content.length;
        }
    }

    /**
     * Downloads one version and verifies it.
     *
     * @param pluginId         the plugin
     * @param version          the version
     * @param expectedChecksum the SHA-256 the catalogue publishes for this version
     * @return the verified archive
     * @throws PluginValidationException              when the checksum is absent or does not match
     * @throws PluginServerUnavailableException       when the registry cannot be reached or refuses
     */
    public VerifiedArchive fetch(String pluginId, String version, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isBlank()) {
            throw new PluginValidationException("The registry publishes no checksum for " + pluginId + ":"
                    + version + ", so the archive cannot be verified. Refusing to install it.");
        }

        byte[] content = read(pluginId, version);
        String actual = sha256(content);
        if (!actual.equalsIgnoreCase(expectedChecksum.trim())) {
            // Deliberately does not say what the bytes hashed to beyond the log: the useful fact for a user is
            // that the archive is not the published one, and the details belong in the operator's log.
            log.error("Checksum mismatch installing {}:{}. Expected {}, downloaded {} ({} bytes)", pluginId,
                    version, expectedChecksum, actual, content.length);
            throw new PluginValidationException("The archive downloaded for " + pluginId + ":" + version
                    + " does not match the checksum the registry published for it. Nothing was installed.");
        }

        String cachePath = promote(pluginId, version, content);
        log.info("Verified {}:{} ({} bytes, sha256 {}) into {}", pluginId, version, content.length, actual,
                cachePath);
        return new VerifiedArchive(pluginId, version, content, actual, cachePath,
                fileNameFor(pluginId, version));
    }

    private byte[] read(String pluginId, String version) {
        try (InputStream stream = client.download(pluginId, version)) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new PluginServerUnavailableException("The download of " + pluginId + ":" + version
                    + " could not be read: " + ex.getMessage(), ex);
        }
    }

    /**
     * Writes the verified bytes into the cache.
     *
     * <p>A failure to write is logged and swallowed. The cache is a convenience, and an engine with a read-only
     * or full working directory should still be able to install a plugin, because the copy that matters goes to
     * GridFS.
     */
    private String promote(String pluginId, String version, byte[] content) {
        String relative = safeSegment(pluginId) + "/" + safeSegment(version) + "/"
                + fileNameFor(pluginId, version);
        try {
            Path target = root().resolve(relative.replace('/', java.io.File.separatorChar));
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return relative;
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not promote {}:{} into the plugin cache: {}", pluginId, version, ex.getMessage());
            return null;
        }
    }

    /**
     * Removes a version's cached archive, after an uninstall or a failed install.
     *
     * @param pluginId the plugin
     * @param version  the version
     */
    public void release(String pluginId, String version) {
        Path directory = root().resolve(safeSegment(pluginId)).resolve(safeSegment(version));
        if (!Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            // Leave no empty plugin directory behind once its last version has gone, so the cache reflects
            // what is installed rather than what once was.
            Path parent = directory.getParent();
            try (java.util.stream.Stream<Path> remaining = Files.list(parent)) {
                if (remaining.findAny().isEmpty()) {
                    Files.deleteIfExists(parent);
                }
            }
        } catch (IOException | UncheckedIOException ex) {
            // A stale cache directory is untidy, not harmful: nothing loads from it.
            log.warn("Could not clear the cache directory of {}:{}: {}", pluginId, version, ex.getMessage());
        }
    }

    /** @return the cache root, as configured */
    public Path root() {
        return Path.of(properties.getCacheDirectory());
    }

    private static String fileNameFor(String pluginId, String version) {
        return safeSegment(pluginId) + "-" + safeSegment(version) + ".jar";
    }

    /**
     * Reduces a registry-supplied identifier to something that cannot escape the cache directory.
     *
     * <p>The plugin id and version arrive from another service. Resolving them into a path unchecked is how a
     * {@code ../../} in a plugin id turns a download into an arbitrary file write.
     */
    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        // A segment of dots would still traverse after the character filter has run.
        return cleaned.chars().allMatch(character -> character == '.') ? "unknown" : cleaned;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", ex);
        }
    }
}
