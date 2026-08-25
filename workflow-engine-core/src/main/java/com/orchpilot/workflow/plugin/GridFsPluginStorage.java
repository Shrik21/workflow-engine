package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.exception.PluginLoadException;
import com.orchpilot.workflow.utility.HashUtils;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

/**
 * Stores plugin JARs in MongoDB GridFS.
 *
 * <p>GridFS chunks the binary, so a plugin larger than the 16 MB document limit is stored without special
 * handling, and reads stream rather than materialising the whole archive in the driver.
 *
 * <p>Reads verify the SHA-256 as the bytes go past, using a {@link DigestOutputStream} so the file is not
 * read twice. A mismatch aborts the load and deletes the partial file: refusing to run a JAR whose bytes
 * are not the ones that were reviewed is the entire value of recording a checksum.
 */
@Component
public class GridFsPluginStorage implements PluginJarStorage {

    private static final Logger log = LoggerFactory.getLogger(GridFsPluginStorage.class);

    private static final String CONTENT_TYPE = "application/java-archive";

    private final GridFsOperations gridFs;

    public GridFsPluginStorage(GridFsOperations gridFs) {
        this.gridFs = gridFs;
    }

    @Override
    public StoredJar store(String pluginId, String version, String fileName, byte[] content) {
        String sha256 = HashUtils.sha256Hex(content);
        Document metadata = new Document();
        metadata.put("pluginId", pluginId);
        metadata.put("version", version);
        metadata.put("sha256", sha256);
        metadata.put("uploadedAt", Instant.now().toString());
        String storedName = pluginId + "-" + version + ".jar";
        try (InputStream in = new ByteArrayInputStream(content)) {
            Object fileId = gridFs.store(in, storedName, CONTENT_TYPE, metadata);
            log.info("Stored plugin JAR {} ({} bytes, sha256 {}) in GridFS as {}", storedName,
                    content.length, sha256, fileId);
            return new StoredJar(String.valueOf(fileId), content.length, sha256);
        } catch (IOException ex) {
            throw new PluginLoadException(pluginId + ":" + version, "could not store the JAR in GridFS", ex);
        }
    }

    @Override
    public long writeTo(String fileId, String expectedSha256, Path target) {
        GridFSFile file = findFile(fileId);
        if (file == null) {
            throw new PluginLoadException(String.valueOf(fileId), "the stored JAR is missing from GridFS");
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ex) {
            throw new PluginLoadException(String.valueOf(fileId),
                    "could not create the plugin workspace directory", ex);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }

        long written;
        GridFsResource resource = gridFs.getResource(file);
        try (InputStream in = resource.getInputStream();
             OutputStream fileOut = Files.newOutputStream(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             DigestOutputStream out = new DigestOutputStream(fileOut, digest)) {
            written = in.transferTo(out);
        } catch (IOException ex) {
            deleteQuietly(target);
            throw new PluginLoadException(String.valueOf(fileId), "could not stage the JAR locally", ex);
        }

        String actual = toHex(digest.digest());
        if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equalsIgnoreCase(actual)) {
            deleteQuietly(target);
            throw new PluginLoadException(String.valueOf(fileId),
                    "checksum mismatch: expected " + expectedSha256 + " but the stored bytes hash to "
                            + actual + ". Refusing to load a JAR whose contents have changed.");
        }
        log.debug("Staged plugin JAR {} to {} ({} bytes, sha256 verified)", fileId, target, written);
        return written;
    }

    @Override
    public boolean delete(String fileId) {
        GridFSFile file = findFile(fileId);
        if (file == null) {
            return false;
        }
        gridFs.delete(idQuery(fileId));
        log.info("Deleted plugin JAR {} from GridFS", fileId);
        return true;
    }

    @Override
    public Optional<Long> size(String fileId) {
        GridFSFile file = findFile(fileId);
        return file == null ? Optional.empty() : Optional.of(file.getLength());
    }

    private GridFSFile findFile(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return null;
        }
        try {
            return gridFs.findOne(idQuery(fileId));
        } catch (RuntimeException ex) {
            log.warn("Lookup of GridFS file {} failed: {}", fileId, ex.getMessage());
            return null;
        }
    }

    private static Query idQuery(String fileId) {
        // GridFS ids are ObjectIds; accept the hex form the rest of the engine carries as a string.
        Object id = org.bson.types.ObjectId.isValid(fileId) ? new org.bson.types.ObjectId(fileId) : fileId;
        return Query.query(Criteria.where("_id").is(id));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.debug("Could not delete partial staging file {}: {}", path, ex.getMessage());
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
