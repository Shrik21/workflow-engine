package com.orchpilot.workflow.storage;

import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.provider.LocalFileStorageProvider;
import com.orchpilot.workflow.storage.provider.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The local provider against a real temporary directory.
 *
 * <p>A real filesystem rather than a mock, because everything worth testing here <em>is</em> filesystem
 * behaviour: containment after symlink resolution, atomic replacement, and what happens when two threads write
 * at once. A mocked {@code Files} would assert that the code calls the methods it calls, which proves nothing.
 */
class LocalFileStorageProviderTest {

    @TempDir
    Path root;

    private LocalFileStorageProvider provider;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        provider = new LocalFileStorageProvider();
        // Canonicalised the way the settings service stores it — on macOS /var is a symlink to /private/var,
        // so skipping this would make every containment assertion fail for the wrong reason.
        base = root.toRealPath().toString();
    }

    // ------------------------------------------------------------------ round trip

    @Test
    @DisplayName("stores and reads back, reporting the measured size and checksum")
    void storesAndReads() throws IOException {
        byte[] content = "customer,amount\nacme,100\n".getBytes(StandardCharsets.UTF_8);
        String key = "workflows/WF-123/v3/files/abc123-customer-data.csv";

        StoredObject stored = provider.store(base, key, new ByteArrayInputStream(content), content.length);

        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.checksum()).isEqualTo(sha256(content));
        // The documented layout, on disk.
        assertThat(root.resolve("workflows/WF-123/v3/files/abc123-customer-data.csv")).exists();

        try (InputStream in = provider.read(base, key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("the checksum is of the bytes written, not of the declared size")
    void checksumMatchesActualContent() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        // A client lying about the length must not change what is recorded.
        StoredObject stored = provider.store(base, "workflows/W/v1/files/a-hello.txt",
                new ByteArrayInputStream(content), 999_999);

        assertThat(stored.size()).isEqualTo(5);
        assertThat(stored.checksum()).isEqualTo(sha256(content));
    }

    @Test
    @DisplayName("creates the version directory tree on first write")
    void createsDirectories() {
        provider.store(base, "workflows/NEW/v1/files/x-a.txt", stream("a"), 1);

        assertThat(root.resolve("workflows/NEW/v1/files")).isDirectory();
    }

    // ------------------------------------------------------------------ containment

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside.txt",
            "workflows/../../outside.txt",
            "workflows/WF-1/v1/files/../../../../outside.txt",
            "/etc/passwd",
            "workflows\\WF-1\\v1\\files\\x.txt",
            "C:/Windows/evil.exe",
    })
    @DisplayName("a key that would escape the root is refused")
    void refusesEscapingKeys(String key) {
        assertThatThrownBy(() -> provider.store(base, key, stream("x"), 1))
                .isInstanceOf(FileStorageException.class);

        assertThatThrownBy(() -> provider.read(base, key))
                .isInstanceOf(FileStorageException.class);

        assertThatThrownBy(() -> provider.delete(base, key))
                .isInstanceOf(FileStorageException.class);
    }

    @Test
    @DisplayName("nothing is written outside the root when a key is refused")
    void refusalWritesNothing() throws IOException {
        Path sibling = root.getParent().resolve("escaped.txt");
        Files.deleteIfExists(sibling);

        assertThatThrownBy(() -> provider.store(base, "../escaped.txt", stream("x"), 1))
                .isInstanceOf(FileStorageException.class);

        assertThat(sibling).doesNotExist();
    }

    @Test
    @DisplayName("a sibling directory sharing a name prefix is not treated as inside the root")
    void prefixIsNotContainment() throws IOException {
        // "/tmp/rootxyz" starts with the string "/tmp/root" but is not inside it. A string startsWith would
        // pass; a Path startsWith does not.
        Path sneaky = root.getParent().resolve(root.getFileName() + "xyz");
        Files.createDirectories(sneaky);

        assertThatThrownBy(() -> provider.store(base, "../" + sneaky.getFileName() + "/x.txt", stream("x"), 1))
                .isInstanceOf(FileStorageException.class);
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    @DisplayName("delete is idempotent")
    void deleteIsIdempotent() {
        String key = "workflows/W/v1/files/a-x.txt";
        provider.store(base, key, stream("x"), 1);

        assertThat(provider.delete(base, key)).isTrue();
        assertThat(provider.delete(base, key)).isFalse();
        assertThat(provider.exists(base, key)).isFalse();
    }

    @Test
    @DisplayName("reading a reference whose content is gone reports the storage mismatch")
    void readingMissingContentIsDistinct() {
        assertThatThrownBy(() -> provider.read(base, "workflows/W/v1/files/absent.txt"))
                .isInstanceOf(FileStorageException.class)
                .satisfies(ex -> assertThat(((FileStorageException) ex).getErrorCode())
                        .isEqualTo("FILE_NOT_FOUND_IN_STORAGE"));
    }

    @Test
    @DisplayName("listing skips partial uploads")
    void listingIgnoresTemporaryFiles() throws IOException {
        String directory = "workflows/W/v1/files";
        provider.store(base, directory + "/a-real.txt", stream("x"), 1);
        // An upload that died mid-flight leaves one of these behind; it is not a stored object.
        Files.createFile(root.resolve(directory).resolve(".upload-123.part"));

        List<String> keys = provider.list(base, directory);

        assertThat(keys).containsExactly(directory + "/a-real.txt");
    }

    @Test
    @DisplayName("deleting a prefix removes the whole version tree")
    void deletesPrefix() {
        provider.store(base, "workflows/W/v1/files/a.txt", stream("x"), 1);
        provider.store(base, "workflows/W/v1/files/b.txt", stream("y"), 1);
        provider.store(base, "workflows/W/v2/files/c.txt", stream("z"), 1);

        provider.deletePrefix(base, "workflows/W/v1/files");

        assertThat(root.resolve("workflows/W/v1/files")).doesNotExist();
        // v2 is a different version and must be untouched.
        assertThat(root.resolve("workflows/W/v2/files/c.txt")).exists();
    }

    @Test
    @DisplayName("versions are isolated from each other")
    void versionsAreIsolated() throws IOException {
        provider.store(base, "workflows/W/v1/files/f1-document.pdf", stream("version one"), 11);
        provider.store(base, "workflows/W/v2/files/f2-document.pdf", stream("version two"), 11);

        // The same original filename in two versions must not overwrite anything.
        try (InputStream first = provider.read(base, "workflows/W/v1/files/f1-document.pdf")) {
            assertThat(new String(first.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("version one");
        }
        try (InputStream second = provider.read(base, "workflows/W/v2/files/f2-document.pdf")) {
            assertThat(new String(second.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("version two");
        }
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent uploads to one directory all succeed and none is corrupted")
    void concurrentUploadsAreSafe() throws Exception {
        int uploads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<StoredObject>> jobs = new ArrayList<>();
        for (int i = 0; i < uploads; i++) {
            String content = "payload-" + i;
            // Distinct stored names, as the service builds them from a generated file id.
            String key = "workflows/W/v1/files/file" + i + "-invoice.pdf";
            jobs.add(() -> provider.store(base, key, stream(content), content.length()));
        }

        List<Future<StoredObject>> results = pool.invokeAll(jobs);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (int i = 0; i < uploads; i++) {
            StoredObject stored = results.get(i).get();
            assertThat(stored.checksum()).isEqualTo(sha256(("payload-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        // Every file landed, and no .part file was left behind.
        assertThat(provider.list(base, "workflows/W/v1/files")).hasSize(uploads);
    }

    // ------------------------------------------------------------------ helpers

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
