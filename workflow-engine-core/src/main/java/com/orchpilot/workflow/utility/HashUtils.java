package com.orchpilot.workflow.utility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hashing helpers used for plugin JAR checksums, workflow definition fingerprints and idempotency
 * keys.
 */
public final class HashUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HashUtils() {
    }

    /**
     * @param data bytes to digest
     * @return lower-case hex SHA-256
     */
    public static String sha256Hex(byte[] data) {
        return toHex(digest(data));
    }

    /**
     * @param text text to digest as UTF-8
     * @return lower-case hex SHA-256
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stable fingerprint of an arbitrary configuration tree.
     *
     * <p>Map keys are sorted before hashing, so two configurations that differ only in property
     * order produce the same fingerprint. That property is what makes the idempotency key survive a
     * round trip through MongoDB, where document key order is not guaranteed to be preserved.
     *
     * @param value map, list or scalar
     * @return lower-case hex SHA-256 of the canonical rendering
     */
    public static String fingerprint(Object value) {
        StringBuilder sb = new StringBuilder(128);
        canonicalize(value, sb);
        return sha256Hex(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static void canonicalize(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map) {
            out.append('{');
            Map<String, Object> sorted = new TreeMap<>();
            ((Map<String, Object>) value).forEach((k, v) -> sorted.put(String.valueOf(k), v));
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(entry.getKey()).append('=');
                canonicalize(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<Object>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                canonicalize(item, out);
            }
            out.append(']');
        } else {
            out.append(value);
        }
    }

    private static byte[] digest(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(chars);
    }
}
