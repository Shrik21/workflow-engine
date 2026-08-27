package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.exception.PluginValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one place a substituted archive can still be caught cheaply.
 *
 * <p>Everything downstream treats these bytes as safe enough to load into a class loader, so the tests that matter
 * here are the refusals: a checksum that does not match, and a catalogue that publishes no checksum at all. The
 * second is the easier one to get wrong, because installing anyway looks like tolerance rather than the hole it is.
 */
class PluginArchiveDownloaderTest {

    private static final byte[] ARCHIVE = "a plugin archive, pretend it is a jar"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cacheRoot;

    private PluginServerClient client;
    private PluginArchiveDownloader downloader;

    @BeforeEach
    void setUp() {
        client = mock(PluginServerClient.class);
        PluginServerProperties properties = new PluginServerProperties();
        properties.setCacheDirectory(cacheRoot.toString());
        downloader = new PluginArchiveDownloader(client, properties);
        // A fresh stream per call: a single instance would be exhausted by the first download, and the second
        // would read nothing and fail its checksum for a reason that has nothing to do with the code.
        when(client.download(anyString(), anyString()))
                .thenAnswer(invocation -> new ByteArrayInputStream(ARCHIVE));
    }

    private static String checksumOf(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    @Test
    @DisplayName("a matching checksum yields the bytes and promotes them into the cache")
    void verifies() throws Exception {
        String expected = checksumOf(ARCHIVE);

        PluginArchiveDownloader.VerifiedArchive archive = downloader.fetch("slack", "1.0.0", expected);

        assertArrayEquals(ARCHIVE, archive.content());
        assertEquals(expected, archive.sha256());
        assertEquals(ARCHIVE.length, archive.size());
        assertEquals("slack-1.0.0.jar", archive.fileName());
        assertTrue(Files.exists(cacheRoot.resolve("slack").resolve("1.0.0").resolve("slack-1.0.0.jar")));
    }

    @Test
    @DisplayName("a mismatched checksum is refused and nothing is cached")
    void refusesMismatch() {
        PluginValidationException failure = assertThrows(PluginValidationException.class,
                () -> downloader.fetch("slack", "1.0.0", "0".repeat(64)));

        assertTrue(failure.getMessage().contains("does not match the checksum"));
        assertFalse(Files.exists(cacheRoot.resolve("slack")));
    }

    @Test
    @DisplayName("an absent checksum is refused rather than treated as nothing to check")
    void refusesAbsentChecksum() {
        assertThrows(PluginValidationException.class, () -> downloader.fetch("slack", "1.0.0", null));
        assertThrows(PluginValidationException.class, () -> downloader.fetch("slack", "1.0.0", "  "));
    }

    @Test
    @DisplayName("a plugin id that tries to traverse cannot escape the cache directory")
    void refusesTraversal() throws Exception {
        PluginArchiveDownloader.VerifiedArchive archive = downloader.fetch("../../evil", "1.0.0",
                checksumOf(ARCHIVE));

        // The id is reduced to a single safe segment, so the write stays under the configured root.
        assertTrue(archive.cachePath().startsWith(".._.._evil/"), archive.cachePath());
        assertTrue(Files.walk(cacheRoot).allMatch(path -> path.startsWith(cacheRoot)));
    }

    @Test
    @DisplayName("releasing removes a version's cached archive")
    void releases() throws Exception {
        downloader.fetch("slack", "1.0.0", checksumOf(ARCHIVE));
        assertTrue(Files.exists(cacheRoot.resolve("slack").resolve("1.0.0")));

        downloader.release("slack", "1.0.0");

        assertFalse(Files.exists(cacheRoot.resolve("slack").resolve("1.0.0")));
        // The plugin directory goes too once its last version has, so the cache shows what is installed.
        assertFalse(Files.exists(cacheRoot.resolve("slack")));
    }

    @Test
    @DisplayName("releasing one version leaves another version's archive alone")
    void releasesOnlyOneVersion() throws Exception {
        downloader.fetch("slack", "1.0.0", checksumOf(ARCHIVE));
        downloader.fetch("slack", "1.2.0", checksumOf(ARCHIVE));

        downloader.release("slack", "1.0.0");

        assertFalse(Files.exists(cacheRoot.resolve("slack").resolve("1.0.0")));
        assertTrue(Files.exists(cacheRoot.resolve("slack").resolve("1.2.0")));
    }
}
