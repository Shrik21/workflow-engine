package com.orchpilot.workflow.plugins.email;

import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * One file to attach.
 *
 * <h2>Where the bytes come from, and where they do not</h2>
 *
 * An attachment names a source the workflow already holds: a variable carrying content, or a base64 value
 * produced upstream. It cannot name a filesystem path. A node that could attach {@code /etc/passwd} — or a
 * path assembled from a workflow variable somebody else controls — turns "send an email" into "read any file
 * the engine can read and post it off site", which is not a capability an email node should have.
 *
 * <p>Sources that need the engine to fetch something — object storage, a form upload held outside the
 * execution — are declared here so a configuration naming them is understood, and are refused at execution
 * with a message saying so, rather than silently sending an empty file.
 */
record EmailAttachment(String fileName, Source source, String value, String contentType) {

    /** Where an attachment's bytes come from. */
    enum Source {

        /** A workflow variable holding the content, resolved through the engine's own resolver. */
        VARIABLE,

        /** A base64 string, either inline or resolved from a variable. */
        BASE64,

        /**
         * A file the engine holds for this workflow, or a form upload.
         *
         * <p>Declared but not implemented: fetching one needs an engine capability the plugin SDK does not
         * expose. A configuration using it fails with a clear message rather than sending nothing.
         */
        WORKFLOW_FILE,

        /** Object storage. Not implemented here, for the same reason. */
        OBJECT_STORAGE;

        static Source parse(String value) {
            if (value == null || value.isBlank()) {
                return VARIABLE;
            }
            String name = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            return switch (name) {
                case "BASE64", "CONTENT_BASE64", "INLINE" -> BASE64;
                case "WORKFLOW_FILE", "FILE", "FORM_UPLOAD", "FORM" -> WORKFLOW_FILE;
                case "OBJECT_STORAGE", "S3", "STORAGE" -> OBJECT_STORAGE;
                default -> VARIABLE;
            };
        }

        /** @return whether this plugin can produce bytes for this source on its own */
        boolean isSupported() {
            return this == VARIABLE || this == BASE64;
        }
    }

    /**
     * Reads one attachment from a configuration map, resolving variables as it goes.
     *
     * @param raw      the configured entry
     * @param resolver the engine's variable resolver, applied to every text field
     * @return the attachment
     */
    static EmailAttachment from(Map<String, Object> raw, java.util.function.UnaryOperator<String> resolver) {
        String fileName = resolver.apply(text(raw, "fileName", "attachment"));
        Source source = Source.parse(text(raw, "source", null));
        String value = resolver.apply(text(raw, "value", ""));
        String contentType = text(raw, "contentType", "application/octet-stream");
        return new EmailAttachment(fileName, source, value, contentType);
    }

    /**
     * The bytes to attach.
     *
     * @return the content
     * @throws IllegalStateException when the source is one this plugin cannot fetch
     */
    byte[] content() {
        return switch (source) {
            case BASE64 -> decode(value);
            case VARIABLE -> looksLikeBase64(value)
                    ? decode(value)
                    : value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            case WORKFLOW_FILE, OBJECT_STORAGE -> throw new IllegalStateException(
                    "Attachment '" + fileName + "' uses source " + source + ", which this plugin cannot "
                            + "fetch. Put the content in a workflow variable and use VARIABLE or BASE64.");
        };
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Attachment '" + value.length() + " characters' is declared as "
                    + "base64 but is not valid base64.");
        }
    }

    /**
     * A cheap check, on purpose.
     *
     * <p>A variable may hold either text to attach as-is or base64 produced upstream, and there is no marker
     * distinguishing them. Long, correctly padded, and drawn only from the base64 alphabet is a good enough
     * guess; anything else is treated as text, which is the harmless direction to be wrong in.
     */
    private static boolean looksLikeBase64(String value) {
        String trimmed = value.trim();
        return trimmed.length() > 64 && trimmed.length() % 4 == 0
                && trimmed.matches("[A-Za-z0-9+/]+={0,2}");
    }

    private static String text(Map<String, Object> raw, String key, String fallback) {
        Object value = raw.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
