package com.orchpilot.workflow.storage.provider;

/**
 * What a provider actually wrote.
 *
 * <p>The size and checksum are measured during the write rather than taken from the request. A multipart part's
 * declared size is a client-supplied number and a truncated upload will report the size it meant to send, so
 * trusting it would record a file as complete when it is not. Measuring while streaming costs nothing — the
 * bytes are already passing through — and makes the stored metadata describe the file that exists.
 *
 * @param relativeKey the key it was written under
 * @param size        bytes actually written
 * @param checksum    lowercase hex SHA-256 of those bytes
 */
public record StoredObject(String relativeKey, long size, String checksum) {
}
