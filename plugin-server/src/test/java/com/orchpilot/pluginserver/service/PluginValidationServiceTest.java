package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.config.PluginServerProperties;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.workflow.sdk.manifest.PluginManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the registry accepts, and what it refuses.
 *
 * <p>The property under test throughout: every decision is made by reading the archive as data. Nothing here loads
 * a class, and the tests build real zip files rather than mocking the inspection, because the interesting failures
 * are all about archive structure.
 */
class PluginValidationServiceTest {

    private final PluginValidationService validation = new PluginValidationService(properties());

    private static PluginServerProperties properties() {
        PluginServerProperties properties = new PluginServerProperties();
        properties.getRegistry().setMaxJarSize(DataSize.ofMegabytes(8));
        return properties;
    }

    private static final String MANIFEST = """
            {
              "pluginId": "sendgrid",
              "name": "SendGrid Plugin",
              "version": "1.2.0",
              "mainClass": "com.example.sendgrid.SendGridPlugin",
              "sdkVersion": "1.0.0",
              "javaVersion": "17",
              "pluginType": "NODE",
              "nodes": [{ "nodeType": "SENDGRID_EMAIL", "displayName": "Send Email",
                          "category": "Communication" }]
            }
            """;

    /** Builds a zip from entry name to content. */
    private static byte[] archive(Map<String, String> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }

    private static byte[] validArchive() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(PluginManifest.LOCATION, MANIFEST);
        entries.put("com/example/sendgrid/SendGridPlugin.class", "not really bytecode, never loaded");
        return archive(entries);
    }

    @Test
    @DisplayName("accepts a well-formed archive and reports what is in it")
    void acceptsValidArchive() {
        byte[] content = validArchive();

        PluginValidationService.Inspection inspection = validation.inspect("sendgrid-1.2.0.jar", content);

        assertEquals("sendgrid", inspection.manifest().pluginId());
        assertEquals("1.2.0", inspection.manifest().version());
        assertEquals(2, inspection.entryCount());
        assertEquals(content.length, inspection.sizeBytes());
        assertFalse(inspection.signed());
        assertEquals(64, inspection.checksum().length(), "SHA-256 is 32 bytes of lower-case hex");
        assertTrue(inspection.checksum().matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("the checksum is stable for the same bytes and different for different bytes")
    void checksumIsContentAddressed() {
        byte[] content = validArchive();

        assertEquals(validation.sha256(content), validation.sha256(content));
        assertFalse(validation.sha256(content).equals(validation.sha256(new byte[]{1, 2, 3})));
        // The published digest of empty input, which is a useful canary that this is really SHA-256 and really
        // lower-case hex rather than something that merely looks like a hash.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                validation.sha256(new byte[0]));
    }

    @Test
    @DisplayName("refuses an archive with no manifest, and says what to add")
    void refusesArchiveWithoutManifest() {
        byte[] content = archive(Map.of("com/example/Thing.class", "x"));

        PluginServerException failure = assertThrows(PluginServerException.class,
                () -> validation.inspect("thing.jar", content));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatus());
        assertTrue(failure.getMessage().contains(PluginManifest.LOCATION));
    }

    @Test
    @DisplayName("refuses a manifest whose mainClass is not in the archive")
    void refusesMissingMainClass() {
        // The one cross-check possible without loading anything: the named class must at least be present.
        byte[] content = archive(Map.of(PluginManifest.LOCATION, MANIFEST));

        PluginServerException failure = assertThrows(PluginServerException.class,
                () -> validation.inspect("sendgrid.jar", content));

        assertTrue(failure.getDetails().stream().anyMatch(problem ->
                        problem.contains("com/example/sendgrid/SendGridPlugin.class")),
                () -> "should name the missing entry: " + failure.getDetails());
    }

    @Test
    @DisplayName("reports every manifest problem in one response")
    void reportsEveryProblem() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(PluginManifest.LOCATION, "{\"pluginId\":\"Bad Id\",\"version\":\"1.2\"}");
        entries.put("a/B.class", "x");

        PluginServerException failure = assertThrows(PluginServerException.class,
                () -> validation.inspect("bad.jar", archive(entries)));

        assertTrue(failure.getDetails().size() >= 3,
                () -> "an author should see every problem at once, got: " + failure.getDetails());
    }

    @Test
    @DisplayName("refuses an entry whose path escapes the archive")
    void refusesPathTraversal() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(PluginManifest.LOCATION, MANIFEST);
        entries.put("com/example/sendgrid/SendGridPlugin.class", "x");
        entries.put("../../../etc/passwd", "malicious");

        PluginServerException failure = assertThrows(PluginServerException.class,
                () -> validation.inspect("evil.jar", archive(entries)));

        // Harmless in this service, which never extracts. Refused here so the workflow service, which does
        // unpack these to a cache directory, cannot be the first place it matters.
        assertTrue(failure.getDetails().stream().anyMatch(p -> p.contains("escapes the archive")),
                () -> failure.getDetails().toString());
    }

    @Test
    @DisplayName("refuses an empty upload and one that is not an archive")
    void refusesNonArchives() {
        assertThrows(PluginServerException.class, () -> validation.inspect("empty.jar", new byte[0]));
        assertThrows(PluginServerException.class, () -> validation.inspect("x.jar", null));

        PluginServerException failure = assertThrows(PluginServerException.class,
                () -> validation.inspect("text.jar", "I am not a zip file".getBytes()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatus());
    }

    @Test
    @DisplayName("refuses an archive above the configured size, with the limit in the message")
    void refusesOversizedArchive() {
        PluginServerProperties tight = properties();
        tight.getRegistry().setMaxJarSize(DataSize.ofBytes(64));
        PluginValidationService strict = new PluginValidationService(tight);

        PluginServerException failure = assertThrows(PluginServerException.class,
                () -> strict.inspect("big.jar", validArchive()));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, failure.getStatus());
        assertTrue(failure.getMessage().contains("64"));
    }

    @Test
    @DisplayName("notices a signed archive without needing to verify the signature")
    void detectsSignature() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(PluginManifest.LOCATION, MANIFEST);
        entries.put("com/example/sendgrid/SendGridPlugin.class", "x");
        entries.put("META-INF/SIGNER.SF", "signature file");
        entries.put("META-INF/SIGNER.RSA", "signature block");

        assertTrue(validation.inspect("signed.jar", archive(entries)).signed());
    }

    @Test
    @DisplayName("accepts an archive whose name does not end in .jar, because the name proves nothing")
    void acceptsOddFileName() {
        assertEquals("sendgrid", validation.inspect("upload.bin", validArchive()).manifest().pluginId());
    }

    @Test
    @DisplayName("accepts an archive whose entries use Windows separators")
    void acceptsBackslashSeparators() {
        /*
         * The ZIP specification requires forward slashes and Maven always writes them, but .NET's
         * ZipFile.CreateFromDirectory writes backslashes, and a plugin packaged with a PowerShell script on
         * Windows arrives looking like this. Before it was normalised, such an archive was refused with "the
         * archive has no META-INF/workflow-plugin.json" while visibly containing exactly that file.
         */
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF\\workflow-plugin.json", MANIFEST);
        entries.put("com\\example\\sendgrid\\SendGridPlugin.class", "x");

        PluginValidationService.Inspection inspection = validation.inspect("windows.jar", archive(entries));

        assertEquals("sendgrid:1.2.0", inspection.manifest().coordinate());
    }
}
