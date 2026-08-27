package com.orchpilot.workflow.storage.util;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an uploaded filename into something safe to append to a path.
 *
 * <h2>The threat</h2>
 *
 * An uploaded filename is attacker-controlled text that this application is about to use to name a file on its
 * own disk. Everything below is a real technique, not a hypothetical:
 *
 * <ul>
 *   <li>{@code ../../etc/passwd} — climb out of the storage root.</li>
 *   <li>{@code ..\..\windows\system32\x} — the same with the other separator, which a Linux-only
 *       {@code contains("/")} check misses entirely and Windows then honours.</li>
 *   <li>{@code C:\evil.txt}, {@code \\attacker\share\x} — absolute and UNC paths that ignore the root.</li>
 *   <li>{@code %2e%2e%2fx} — percent-encoded traversal, in case something decodes downstream.</li>
 *   <li>{@code CON}, {@code PRN}, {@code LPT1} — Windows device names, which open a device rather than a file.</li>
 *   <li>{@code x.txt.} / {@code x.txt } — Windows silently strips trailing dots and spaces, so
 *       {@code "safe.txt."} and {@code "safe.txt"} are the same file to the OS but different strings to a check.</li>
 *   <li>NUL bytes — historically truncate a path in native code.</li>
 * </ul>
 *
 * <h2>The approach: allow-list, not deny-list</h2>
 *
 * Trying to strip the bad shapes is a losing game — there is always another encoding. Instead, the last path
 * segment is taken and then every character outside a small allow-list is replaced. A name like
 * {@code ../../secret.txt} cannot survive that as anything but {@code secret.txt}, because the traversal is
 * removed by the segmenting step and could not have survived the allow-list anyway.
 *
 * <p>This is defence in depth, not the only defence: the sanitised name is combined with a generated file id and
 * the result is still checked for containment under the storage root before anything is written. Any one of the
 * three would have to fail before a traversal succeeded.
 */
public final class FilenameSanitizer {

    /** What a stored filename may contain. Everything else becomes an underscore. */
    private static final String ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-_ ";

    /**
     * Reserved device names on Windows. Reserved with or without an extension, so {@code CON.txt} is also a
     * device — which is why the check is on the stem rather than on the whole name.
     */
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** Long enough for any real filename, short enough to stay well inside every filesystem's limit. */
    private static final int MAX_LENGTH = 120;

    /** Used when nothing usable survives, e.g. the name was {@code ".."} or entirely non-ASCII punctuation. */
    public static final String FALLBACK = "file";

    private FilenameSanitizer() {
    }

    /**
     * Reduces an uploaded filename to a safe basename.
     *
     * @param original the client-supplied name; may be null, blank, or hostile
     * @return a non-blank name containing no separators, no traversal and no device name
     */
    public static String sanitize(String original) {
        if (original == null || original.isBlank()) {
            return FALLBACK;
        }

        // Decode first, so a percent-encoded separator is caught by the segmenting below rather than surviving
        // as literal text that something downstream might decode later.
        String working = decodeIfEncoded(original);

        // Drop everything up to the last separator of either flavour. This is what removes traversal: the ".."
        // segments are simply not the last segment. Also handles "C:\x" and "\\host\share\x".
        int lastSeparator = Math.max(working.lastIndexOf('/'), working.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            working = working.substring(lastSeparator + 1);
        }
        // A Windows drive-relative name such as "C:file.txt" has no separator at all.
        int colon = working.lastIndexOf(':');
        if (colon >= 0) {
            working = working.substring(colon + 1);
        }

        StringBuilder safe = new StringBuilder(working.length());
        for (char character : working.toCharArray()) {
            safe.append(ALLOWED.indexOf(character) >= 0 ? character : '_');
        }

        // Windows discards trailing dots and spaces, so a name ending in them is not the name it appears to be.
        String result = safe.toString().strip();
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length() - 1).strip();
        }
        // Leading dots would make the file hidden on POSIX and, as "." or "..", would not be a filename at all.
        while (result.startsWith(".")) {
            result = result.substring(1);
        }

        if (result.isBlank()) {
            return FALLBACK;
        }
        if (isWindowsDeviceName(result)) {
            result = "_" + result;
        }
        return truncatePreservingExtension(result);
    }

    /**
     * Validates a value used as a directory segment, such as a workflow id.
     *
     * <p>These are platform-generated rather than user-supplied, so this is an assertion that an invariant still
     * holds rather than a filter — which is why it throws instead of cleaning up. A workflow id that could act as
     * a path segment would be a far deeper problem than a bad upload, and quietly rewriting it would hide that.
     *
     * @param segment the candidate segment
     * @param what    what it is, for the error message
     * @return the segment unchanged
     * @throws IllegalArgumentException when it could escape or confuse a path
     */
    public static String requireSafeSegment(String segment, String what) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException(what + " must not be a relative path segment");
        }
        for (char character : segment.toCharArray()) {
            boolean acceptable = Character.isLetterOrDigit(character)
                    || character == '-' || character == '_' || character == '.';
            if (!acceptable) {
                throw new IllegalArgumentException(
                        what + " contains a character that is not allowed in a storage path segment");
            }
        }
        return segment;
    }

    /**
     * Decodes percent-escapes, but only when the input actually looks encoded.
     *
     * <p>Unconditional decoding would corrupt a legitimate filename that contains a literal {@code %}, and a
     * decode that throws on malformed input would reject files rather than sanitising them — so both are avoided
     * by attempting it only when a {@code %} is present and falling back to the raw text on failure.
     */
    private static String decodeIfEncoded(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            // Double-encoded input (%252e) decodes to %2e; one more pass closes that without looping forever.
            return decoded.indexOf('%') >= 0 ? URLDecoder.decode(decoded, StandardCharsets.UTF_8) : decoded;
        } catch (RuntimeException ex) {
            return value;
        }
    }

    private static boolean isWindowsDeviceName(String name) {
        int dot = name.indexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return WINDOWS_DEVICE_NAMES.contains(stem.toUpperCase(Locale.ROOT));
    }

    /** Keeps the extension when shortening, because that is what decides how the file opens. */
    private static String truncatePreservingExtension(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || name.length() - dot > 12) {
            return name.substring(0, MAX_LENGTH);
        }
        String extension = name.substring(dot);
        return name.substring(0, MAX_LENGTH - extension.length()) + extension;
    }
}
