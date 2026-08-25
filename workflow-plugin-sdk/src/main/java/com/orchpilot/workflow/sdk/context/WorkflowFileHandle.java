package com.orchpilot.workflow.sdk.context;

import java.time.Instant;

/**
 * What a plugin is told about a stored workflow file.
 *
 * <h2>Deliberately no path</h2>
 *
 * There is no {@code relativePath}, no absolute path and no storage root. A plugin addresses a file by its
 * {@code fileId} and asks the engine to open it; it never learns where the bytes live. That is what keeps the
 * storage location an engine concern — an administrator can move it, or switch the deployment to object
 * storage, without a single plugin noticing.
 *
 * <p>It is also the security boundary: a plugin that knew the path could try to read around the engine, and
 * every path-handling defence would then have to be reimplemented in each plugin rather than once.
 *
 * @param fileId      opaque identifier, the only thing a plugin needs to read the file again
 * @param fileName    the name a user would recognise, safe to display and to echo in a header
 * @param contentType MIME type as recorded at upload, or {@code application/octet-stream}
 * @param size        size in bytes as actually measured when stored
 * @param checksum    lowercase hex SHA-256 of the content, for integrity checks
 * @param version     the workflow version the file belongs to
 * @param createdAt   when it was stored
 * @param createdBy   the user id that stored it, or {@code system}
 * @since 1.0.0
 */
public record WorkflowFileHandle(String fileId, String fileName, String contentType, long size,
                                 String checksum, int version, Instant createdAt, String createdBy) {

    /** @return the lowercase extension without the dot, or an empty string when the name has none */
    public String extension() {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? ""
                : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
