package com.orchpilot.workflow.portability;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * The proprietary binary container of a {@code .orchpilot} file.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   magic            9 bytes   "ORCHPILOT"
 *   formatVersion    1 byte    the container version (1)
 *   encryptionVersion 1 byte   the crypto scheme version (1 = AES-256-GCM)
 *   mode             1 byte    1 = platform-wrapped key, 2 = password-derived key
 *   keyMeta          u16 len + bytes    JSON: the wrapped key + wrap nonce (platform), or the Argon2 salt and
 *                                       parameters (password). Never the key or the password themselves.
 *   nonce            1 byte len + bytes the GCM nonce for the payload
 *   ciphertext       u32 len + bytes    the AES-256-GCM ciphertext, tag appended
 *   checksum         32 bytes  SHA-256 of every byte above, so truncation or a flipped header byte is caught
 *                              before a single crypto operation runs
 * </pre>
 *
 * <p>Opening the file in a text editor shows the magic and then binary: no workflow JSON, no node names, no
 * structure — the payload is ciphertext. The checksum is a cheap structural guard, not the security boundary;
 * the GCM tag is the security boundary, and it is checked on decrypt regardless of the checksum.
 *
 * <h2>Every length is bounded as it is read</h2>
 *
 * This parses attacker-controlled input, so each length prefix is checked against a ceiling before a single
 * byte is allocated for it. A crafted file claiming a four-gigabyte key-metadata block is rejected at the
 * length, not after an {@code OutOfMemoryError}.
 */
final class OrchPilotFile {

    static final byte[] MAGIC = "ORCHPILOT".getBytes(StandardCharsets.US_ASCII);
    static final int FORMAT_VERSION = 1;
    static final int ENCRYPTION_VERSION = 1;

    static final int MODE_PLATFORM = 1;
    static final int MODE_PASSWORD = 2;

    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_KEY_META = 8 * 1024;
    private static final int MAX_NONCE = 64;
    /** A ciphertext ceiling independent of the HTTP file-size limit, as a second belt-and-braces bound. */
    private static final int MAX_CIPHERTEXT = 128 * 1024 * 1024;

    private OrchPilotFile() {
    }

    /** The parsed, still-encrypted parts of a file. */
    record Parsed(int mode, byte[] keyMeta, byte[] nonce, byte[] ciphertext) {
    }

    /**
     * Assembles a file from its encrypted parts.
     *
     * @param mode       {@link #MODE_PLATFORM} or {@link #MODE_PASSWORD}
     * @param keyMeta    the key-metadata JSON bytes
     * @param nonce      the payload nonce
     * @param ciphertext the AES-GCM ciphertext with tag
     * @return the file bytes
     */
    static byte[] write(int mode, byte[] keyMeta, byte[] nonce, byte[] ciphertext) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(body);
            out.write(MAGIC);
            out.writeByte(FORMAT_VERSION);
            out.writeByte(ENCRYPTION_VERSION);
            out.writeByte(mode);
            out.writeShort(keyMeta.length);
            out.write(keyMeta);
            out.writeByte(nonce.length);
            out.write(nonce);
            out.writeInt(ciphertext.length);
            out.write(ciphertext);
            out.flush();

            byte[] bodyBytes = body.toByteArray();
            byte[] checksum = sha256(bodyBytes);

            ByteArrayOutputStream file = new ByteArrayOutputStream(bodyBytes.length + CHECKSUM_BYTES);
            file.write(bodyBytes);
            file.write(checksum);
            return file.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to assemble the .orchpilot file", ex);
        }
    }

    /**
     * Parses and structurally validates a file, before any decryption.
     *
     * @param bytes the file
     * @return the parsed parts
     * @throws PackageIntegrityException on a bad magic, unsupported version, bad length or bad checksum
     */
    static Parsed read(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length + CHECKSUM_BYTES + 8) {
            throw new PackageIntegrityException("This is not a valid .orchpilot file: it is too short.");
        }

        // The checksum guards the whole body; verify it first so a truncated or edited header is caught here.
        int bodyLength = bytes.length - CHECKSUM_BYTES;
        byte[] body = Arrays.copyOfRange(bytes, 0, bodyLength);
        byte[] declaredChecksum = Arrays.copyOfRange(bytes, bodyLength, bytes.length);
        if (!MessageDigest.isEqual(sha256(body), declaredChecksum)) {
            throw new PackageIntegrityException(
                    "The .orchpilot file failed its integrity checksum. It is corrupted or truncated.");
        }

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));

            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new PackageIntegrityException(
                        "This is not an OrchPilot workflow package: the file signature does not match.");
            }

            int formatVersion = in.readUnsignedByte();
            if (formatVersion != FORMAT_VERSION) {
                throw new PackageIntegrityException("This package is format version " + formatVersion
                        + ", which this environment does not understand (it supports " + FORMAT_VERSION + ").");
            }
            int encryptionVersion = in.readUnsignedByte();
            if (encryptionVersion != ENCRYPTION_VERSION) {
                throw new PackageIntegrityException("This package uses encryption version " + encryptionVersion
                        + ", which this environment does not support.");
            }
            int mode = in.readUnsignedByte();
            if (mode != MODE_PLATFORM && mode != MODE_PASSWORD) {
                throw new PackageIntegrityException("The package declares an unknown protection mode.");
            }

            byte[] keyMeta = readBounded(in, in.readUnsignedShort(), MAX_KEY_META, "key metadata");
            byte[] nonce = readBounded(in, in.readUnsignedByte(), MAX_NONCE, "nonce");
            byte[] ciphertext = readBounded(in, in.readInt(), MAX_CIPHERTEXT, "payload");

            return new Parsed(mode, keyMeta, nonce, ciphertext);
        } catch (PackageIntegrityException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new PackageIntegrityException("The .orchpilot file is malformed.", ex);
        }
    }

    private static byte[] readBounded(DataInputStream in, int length, int max, String what) throws IOException {
        if (length < 0 || length > max) {
            throw new PackageIntegrityException("The package's " + what + " length (" + length
                    + ") is outside the allowed range.");
        }
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
