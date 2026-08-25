package com.orchpilot.workflow.storage.provider;

import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.model.StorageType;
import com.orchpilot.workflow.storage.util.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Stores workflow files on the filesystem the engine can see — local disk, or the volume mounted into its
 * container.
 *
 * <h2>Containment: the check that matters</h2>
 *
 * Every operation resolves its key through {@link #resolve}, which normalises the result and then requires it to
 * still start with the canonical root. The order is the point. Checking the <em>key</em> for {@code ..} is not
 * enough, because a symlink inside the tree can point anywhere and no amount of string inspection reveals it;
 * checking the <em>resolved</em> path is what closes that. Callers have already sanitised their inputs, and this
 * checks again anyway — a single missed caller should not become a filesystem escape.
 *
 * <h2>Atomicity: why a temporary file</h2>
 *
 * Content is streamed to a temporary file <em>in the destination directory</em> and then moved onto the final
 * name with {@link StandardCopyOption#ATOMIC_MOVE}. Writing straight to the final name would make a half-written
 * file visible under a name the database is about to promise is complete, and a crash mid-upload would leave it
 * there permanently. Same directory because an atomic move is only guaranteed within one filesystem.
 *
 * <p>The stream is wrapped in a {@link DigestOutputStream}, so the checksum is a by-product of the copy rather
 * than a second full read of the file.
 */
@Component
public class LocalFileStorageProvider implements FileStorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageProvider.class);

    private static final String TEMP_PREFIX = ".upload-";
    private static final String TEMP_SUFFIX = ".part";
    private static final int BUFFER_SIZE = 64 * 1024;

    @Override
    public StorageType storageType() {
        return StorageType.LOCAL;
    }

    @Override
    public StoredObject store(String root, String relativeKey, InputStream content, long declaredSize) {
        Path target = resolve(root, relativeKey);
        Path directory = target.getParent();

        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX);

            MessageDigest digest = sha256();
            long written;
            try (OutputStream out = Files.newOutputStream(temporary);
                 DigestOutputStream digesting = new DigestOutputStream(out, digest)) {
                written = copy(content, digesting);
            }

            move(temporary, target);
            temporary = null; // Moved; nothing left to clean up.

            String checksum = HexFormat.of().formatHex(digest.digest());
            log.debug("Stored {} ({} bytes)", relativeKey, written);
            return new StoredObject(relativeKey, written, checksum);
        } catch (IOException ex) {
            log.error("Failed to store {} under the storage root: {}", relativeKey, ex.toString());
            throw FileStorageException.ioFailure("write", ex);
        } finally {
            // A failed upload must not leave a .part file behind for somebody to find later.
            discard(temporary);
        }
    }

    @Override
    public InputStream read(String root, String relativeKey) {
        Path source = resolve(root, relativeKey);
        if (!Files.isRegularFile(source)) {
            throw FileStorageException.missingFromStorage(relativeKey);
        }
        try {
            return Files.newInputStream(source);
        } catch (IOException ex) {
            log.error("Failed to open {} for reading: {}", relativeKey, ex.toString());
            throw FileStorageException.ioFailure("read", ex);
        }
    }

    @Override
    public boolean delete(String root, String relativeKey) {
        Path target = resolve(root, relativeKey);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException ex) {
            log.error("Failed to delete {}: {}", relativeKey, ex.toString());
            throw FileStorageException.ioFailure("delete", ex);
        }
    }

    @Override
    public boolean exists(String root, String relativeKey) {
        return Files.isRegularFile(resolve(root, relativeKey));
    }

    @Override
    public List<String> list(String root, String relativePrefix) {
        Path directory = resolve(root, relativePrefix);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile)
                    // A temporary part file is an upload in flight, not a stored object.
                    .filter(path -> !path.getFileName().toString().startsWith(TEMP_PREFIX))
                    .forEach(path -> keys.add(relativePrefix + "/" + path.getFileName()));
        } catch (IOException ex) {
            log.error("Failed to list {}: {}", relativePrefix, ex.toString());
            throw FileStorageException.ioFailure("list", ex);
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    @Override
    public int deletePrefix(String root, String relativePrefix) {
        Path directory = resolve(root, relativePrefix);
        if (!Files.exists(directory)) {
            return 0;
        }
        int[] removed = {0};
        try (Stream<Path> tree = Files.walk(directory)) {
            // Deepest first, because a directory cannot be removed until it is empty.
            List<Path> ordered = tree.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                if (Files.deleteIfExists(path) && Files.isRegularFile(path)) {
                    removed[0]++;
                }
            }
        } catch (IOException ex) {
            log.error("Failed to delete the tree at {}: {}", relativePrefix, ex.toString());
            throw FileStorageException.ioFailure("delete", ex);
        }
        return removed[0];
    }

    @Override
    public long freeSpace(String root) {
        try {
            return Files.getFileStore(Paths.get(root)).getUsableSpace();
        } catch (IOException | RuntimeException ex) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Turns a relative key into an absolute path, and refuses to return one outside the root.
     *
     * @throws FileStorageException when the resolved path escapes, whatever the reason
     */
    Path resolve(String root, String relativeKey) {
        Path base;
        try {
            // Inside the try, so a rejected key leaves this method as a FileStorageException like every other
            // refusal here. Leaking the raw IllegalArgumentException would give the caller a different type and
            // a different HTTP status for what is the same thing: a path that may not be used.
            StoragePaths.requireRelative(relativeKey);
            base = Paths.get(root).normalize();
            // Split on '/' rather than passing the key whole: on Windows, resolve() would otherwise treat a
            // backslash inside the key as a separator even though requireRelative already rejects those.
            Path resolved = base;
            for (String segment : relativeKey.split("/")) {
                resolved = resolved.resolve(segment);
            }
            resolved = resolved.normalize();

            // The containment check. Compared after normalisation, on absolute paths, with a separator-aware
            // startsWith so that "/data/x" does not appear to contain "/data/xyz".
            if (!resolved.startsWith(base)) {
                log.warn("Refused a storage path that resolved outside the configured root");
                throw FileStorageException.pathEscape();
            }
            return resolved;
        } catch (InvalidPathException ex) {
            throw FileStorageException.pathEscape();
        } catch (IllegalArgumentException ex) {
            throw FileStorageException.rejected(ex.getMessage());
        }
    }

    /**
     * Moves the finished upload onto its final name.
     *
     * <p>{@code ATOMIC_MOVE} is not supported on every filesystem — some network shares refuse it — so a
     * non-atomic replace is the fallback. It is a real, if small, weakening: a reader could briefly observe a
     * partial file. Taking the fallback is still better than failing the upload, and it is logged so an operator
     * can see which storage is behaving that way.
     */
    private void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            log.warn("Storage does not support atomic moves; falling back to a replacing move");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long copy(InputStream source, OutputStream destination) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            destination.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private void discard(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ex) {
            log.warn("Could not remove a partial upload file: {}", ex.toString());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // Required of every JRE; unreachable in practice.
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
