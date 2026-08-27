package com.orchpilot.workflow.storage.provider;

import com.orchpilot.workflow.storage.model.StorageType;

import java.io.InputStream;
import java.util.List;

/**
 * The seam between "which bytes, where" and "on what kind of storage".
 *
 * <h2>What is deliberately not in this interface</h2>
 *
 * No {@code Path}, no {@code File}, no absolute location — only relative keys of the shape
 * {@code workflows/WF-1/v3/files/abc-invoice.pdf}. That is what makes an S3 or Azure implementation possible
 * without changing a caller: those have no filesystem, and an interface that leaked one would have to be
 * redesigned the day a second provider arrived rather than merely implemented.
 *
 * <h2>Streams, not byte arrays</h2>
 *
 * {@link #store} takes an {@link InputStream} and {@link #read} returns one, so a 500 MB upload never becomes a
 * 500 MB array on the heap. Callers own closing what they are given; implementations own closing what they open.
 *
 * <h2>Contract for implementations</h2>
 *
 * <ul>
 *   <li>{@code store} must be atomic as observed by a reader: a partially-written file must never be visible
 *       under its final key. Write elsewhere and move.</li>
 *   <li>{@code store} must compute the checksum while streaming, not by re-reading.</li>
 *   <li>Every key must be validated as relative and contained before use. Implementations must not trust that
 *       callers did it.</li>
 *   <li>{@code delete} is idempotent: deleting an absent object succeeds.</li>
 * </ul>
 */
public interface FileStorageProvider {

    /** @return the type this provider serves; the registry dispatches on it */
    StorageType storageType();

    /**
     * Writes an object.
     *
     * @param root          provider-specific root — the canonical base directory for local storage, a bucket
     *                      name for object storage
     * @param relativeKey   POSIX-style key, relative to {@code root}
     * @param content       the bytes; not closed by this method
     * @param declaredSize  size hint from the upload, or -1 when unknown
     * @return what was actually written, including the authoritative size and checksum
     */
    StoredObject store(String root, String relativeKey, InputStream content, long declaredSize);

    /**
     * Opens an object for reading.
     *
     * @return a stream the caller must close
     */
    InputStream read(String root, String relativeKey);

    /**
     * Removes an object.
     *
     * @return whether something was actually removed; false when it was already absent
     */
    boolean delete(String root, String relativeKey);

    boolean exists(String root, String relativeKey);

    /**
     * Lists the keys directly under a prefix.
     *
     * <p>Used by the consistency check to find files on disk that no reference claims — the opposite direction
     * from the database-driven listing the API serves.
     *
     * @param relativePrefix directory-style prefix, without a trailing slash
     * @return relative keys, empty when the prefix does not exist
     */
    List<String> list(String root, String relativePrefix);

    /**
     * Removes a whole prefix and everything under it.
     *
     * <p>Used when a workflow is deleted. Separated from {@link #delete} because "remove one object" and "remove
     * a subtree" have very different consequences if a key is ever wrong.
     *
     * @return the number of objects removed
     */
    int deletePrefix(String root, String relativePrefix);

    /** @return usable space in bytes, or -1 when the concept does not apply (object storage) */
    long freeSpace(String root);
}
