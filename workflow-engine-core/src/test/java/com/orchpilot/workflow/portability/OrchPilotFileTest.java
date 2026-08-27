package com.orchpilot.workflow.portability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The container format: a round-trip preserves every part, and every way a hostile or corrupt file can be
 * malformed is rejected structurally, before a single crypto operation runs.
 */
class OrchPilotFileTest {

    private final byte[] keyMeta = "{\"salt\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
    private final byte[] nonce = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    private final byte[] ciphertext = "ciphertext-with-tag".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("write then read preserves mode, key metadata, nonce and ciphertext")
    void roundTrips() {
        byte[] file = OrchPilotFile.write(OrchPilotFile.MODE_PASSWORD, keyMeta, nonce, ciphertext);
        OrchPilotFile.Parsed parsed = OrchPilotFile.read(file);

        assertThat(parsed.mode()).isEqualTo(OrchPilotFile.MODE_PASSWORD);
        assertThat(parsed.keyMeta()).isEqualTo(keyMeta);
        assertThat(parsed.nonce()).isEqualTo(nonce);
        assertThat(parsed.ciphertext()).isEqualTo(ciphertext);
    }

    @Test
    @DisplayName("the file begins with the ORCHPILOT magic and is otherwise binary")
    void startsWithMagic() {
        byte[] file = OrchPilotFile.write(OrchPilotFile.MODE_PLATFORM, keyMeta, nonce, ciphertext);
        assertThat(new String(Arrays.copyOfRange(file, 0, 9), StandardCharsets.US_ASCII))
                .isEqualTo("ORCHPILOT");
    }

    @Test
    @DisplayName("a flipped body byte fails the checksum")
    void detectsBodyTampering() {
        byte[] file = OrchPilotFile.write(OrchPilotFile.MODE_PLATFORM, keyMeta, nonce, ciphertext);
        file[20] ^= 0x01;

        assertThatThrownBy(() -> OrchPilotFile.read(file))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    @DisplayName("a wrong magic is rejected")
    void rejectsWrongMagic() {
        byte[] file = OrchPilotFile.write(OrchPilotFile.MODE_PLATFORM, keyMeta, nonce, ciphertext);
        // Corrupt the magic, then repair the checksum so the magic check — not the checksum — is what fails.
        file[0] = 'X';
        byte[] repaired = withFreshChecksum(file);

        assertThatThrownBy(() -> OrchPilotFile.read(repaired))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("an unsupported format version is rejected")
    void rejectsUnsupportedVersion() {
        byte[] file = OrchPilotFile.write(OrchPilotFile.MODE_PLATFORM, keyMeta, nonce, ciphertext);
        file[9] = 99; // formatVersion byte, right after the 9-byte magic
        byte[] repaired = withFreshChecksum(file);

        assertThatThrownBy(() -> OrchPilotFile.read(repaired))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageContaining("format version");
    }

    @Test
    @DisplayName("a truncated file is rejected as too short")
    void rejectsTruncated() {
        assertThatThrownBy(() -> OrchPilotFile.read(new byte[]{1, 2, 3}))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageContaining("too short");
    }

    @Test
    @DisplayName("null is rejected")
    void rejectsNull() {
        assertThatThrownBy(() -> OrchPilotFile.read(null))
                .isInstanceOf(PackageIntegrityException.class);
    }

    /** Recomputes the trailing SHA-256 so a body edit passes the checksum and the next check is what fails. */
    private static byte[] withFreshChecksum(byte[] file) {
        byte[] body = Arrays.copyOfRange(file, 0, file.length - 32);
        try {
            byte[] checksum = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            byte[] repaired = Arrays.copyOf(file, file.length);
            System.arraycopy(checksum, 0, repaired, body.length, checksum.length);
            return repaired;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
