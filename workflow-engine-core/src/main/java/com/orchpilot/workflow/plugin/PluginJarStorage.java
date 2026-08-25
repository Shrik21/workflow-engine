package com.orchpilot.workflow.plugin;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Durable storage for plugin JAR binaries.
 *
 * <p>Behind an interface so a deployment can put JARs in object storage instead of MongoDB. The default
 * implementation uses GridFS rather than embedding bytes in a document: a 40 MB plugin exceeds MongoDB's
 * 16 MB document limit outright, and even a 5 MB one would be loaded into memory on every read of the
 * plugin's metadata.
 */
public interface PluginJarStorage {

    /**
     * Stores a JAR.
     *
     * @param pluginId plugin id
     * @param version  plugin version
     * @param fileName original file name, kept for display
     * @param content  the bytes
     * @return a reference to the stored object
     */
    StoredJar store(String pluginId, String version, String fileName, byte[] content);

    /**
     * Streams a stored JAR to a local file, verifying its checksum.
     *
     * <p>Verification on every read, not only on upload: it is the check that catches a corrupted GridFS
     * chunk and a substituted binary alike, and it costs one pass over bytes the engine is reading anyway.
     *
     * @param fileId         storage reference
     * @param expectedSha256 checksum recorded at upload, or {@code null} to skip verification
     * @param target         local path to write
     * @return number of bytes written
     * @throws com.orchpilot.workflow.exception.PluginLoadException when the file is missing or the checksum
     *                                                          does not match
     */
    long writeTo(String fileId, String expectedSha256, Path target);

    /**
     * @param fileId storage reference
     * @return {@code true} when an object was deleted
     */
    boolean delete(String fileId);

    /**
     * @param fileId storage reference
     * @return stored size in bytes, or empty when the object is absent
     */
    Optional<Long> size(String fileId);

    /**
     * A stored JAR.
     *
     * @param fileId  storage reference
     * @param size    size in bytes
     * @param sha256  lower-case hex SHA-256 of the stored bytes
     */
    record StoredJar(String fileId, long size, String sha256) {
    }
}
