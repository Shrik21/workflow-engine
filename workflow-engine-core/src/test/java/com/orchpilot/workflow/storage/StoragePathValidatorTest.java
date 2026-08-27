package com.orchpilot.workflow.storage;

import com.orchpilot.workflow.storage.dto.PathProbeResult;
import com.orchpilot.workflow.storage.validation.StoragePathValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Path validation.
 *
 * <p>The Windows and Linux path-shape tests are OS-gated rather than written to pass everywhere: {@code D:\x} is
 * a valid absolute path on Windows and a perfectly ordinary <em>relative</em> filename on Linux, so a single
 * assertion for both would have to be so weak it tested nothing.
 */
class StoragePathValidatorTest {

    @TempDir
    Path temp;

    private StoragePathValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StoragePathValidator();
    }

    @Test
    @DisplayName("an existing writable directory passes the full write/read/delete cycle")
    void acceptsAWritableDirectory() {
        PathProbeResult result = validator.probe(temp.toString(), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.readable()).isTrue();
        assertThat(result.writable()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.freeSpaceBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("the probe leaves nothing behind")
    void probeCleansUpAfterItself() throws IOException {
        validator.probe(temp.toString(), false);

        try (var entries = Files.list(temp)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    @DisplayName("the stored path is canonical, so containment checks compare against a resolved value")
    void resolvesToACanonicalPath() throws IOException {
        Path nested = temp.resolve("data");
        Files.createDirectories(nested);

        // A path with a redundant traversal must not be stored as typed.
        PathProbeResult result = validator.probe(temp.resolve("data/../data").toString(), false);

        assertThat(result.valid()).isTrue();
        assertThat(result.canonicalPath()).isEqualTo(nested.toRealPath().toString());
        assertThat(result.canonicalPath()).doesNotContain("..");
    }

    @Test
    @DisplayName("a missing directory fails unless creation was requested")
    void refusesAMissingDirectoryByDefault() {
        Path missing = temp.resolve("not-there");

        PathProbeResult refused = validator.probe(missing.toString(), false);

        assertThat(refused.valid()).isFalse();
        assertThat(refused.problems()).isNotEmpty();
        assertThat(missing).doesNotExist();
    }

    @Test
    @DisplayName("a missing directory is created on request")
    void createsWhenAsked() {
        Path missing = temp.resolve("new/nested/data");

        PathProbeResult result = validator.probe(missing.toString(), true);

        assertThat(result.valid()).isTrue();
        assertThat(result.created()).isTrue();
        assertThat(missing).isDirectory();
    }

    @Test
    @DisplayName("a file is not a directory")
    void refusesAFile() throws IOException {
        Path file = Files.createFile(temp.resolve("a-file.txt"));

        PathProbeResult result = validator.probe(file.toString(), false);

        assertThat(result.valid()).isFalse();
        assertThat(result.problems()).anySatisfy(problem -> assertThat(problem).contains("not a directory"));
    }

    @Test
    @DisplayName("a relative path is refused, because it would mean different places in different deployments")
    void refusesRelativePaths() {
        assertThatThrownBy(() -> validator.parseAbsolute("data/orchpilot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");

        assertThat(validator.probe("./data", false).valid()).isFalse();
    }

    @Test
    @DisplayName("blank input is refused")
    void refusesBlankInput() {
        assertThat(validator.probe(null, false).valid()).isFalse();
        assertThat(validator.probe("   ", false).valid()).isFalse();
    }

    @Test
    @DisplayName("a NUL byte in the path is refused")
    void refusesNulByte() {
        assertThatThrownBy(() -> validator.parseAbsolute("/opt/orchpilot\u0000/data"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a Windows path is accepted and a bare drive-relative one is not")
    void acceptsWindowsPaths() {
        assertThat(validator.parseAbsolute("D:\\OrchPilot\\data")).isAbsolute();
        assertThat(validator.parseAbsolute("C:/OrchPilot/data")).isAbsolute();

        // "C:data" is relative to the current directory on drive C, not to its root.
        assertThatThrownBy(() -> validator.parseAbsolute("C:data"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a Linux path is accepted")
    void acceptsLinuxPaths() {
        assertThat(validator.parseAbsolute("/opt/orchpilot/data")).isAbsolute();
        assertThat(validator.parseAbsolute("/var/lib/orchpilot")).isAbsolute();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a directory the process cannot write is reported as unwritable, not as valid")
    void detectsAnUnwritableDirectory() throws IOException {
        Path readOnly = Files.createDirectories(temp.resolve("read-only"));
        // POSIX only: Windows honours ACLs that this cannot express, and the test would be meaningless there.
        Files.setPosixFilePermissions(readOnly, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));

        try {
            PathProbeResult result = validator.probe(readOnly.toString(), false);

            // This is the case Files.isWritable() gets wrong often enough to justify the real write probe.
            assertThat(result.valid()).isFalse();
            assertThat(result.writable()).isFalse();
            assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("write permission"));
        } finally {
            // Restore, or @TempDir cannot clean up.
            Files.setPosixFilePermissions(readOnly,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
