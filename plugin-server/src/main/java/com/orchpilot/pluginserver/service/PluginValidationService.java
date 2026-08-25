package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.config.PluginServerProperties;
import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.workflow.sdk.manifest.PluginManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Decides whether an uploaded archive is a plugin, without running any of it.
 *
 * <h2>The boundary this class defends</h2>
 *
 * <p>Everything here reads the archive as data: entry names, entry sizes, and the bytes of one JSON file. No class
 * loader is created, no class is defined, no annotation is read reflectively, no main class is instantiated. That
 * is the whole reason the registry can be trusted with a store of arbitrary uploaded executables.
 *
 * <p>It is also why the checks are structural rather than semantic. This service can tell you that the archive
 * declares {@code com.example.SendGridPlugin} and that a matching {@code .class} entry exists; it cannot tell you
 * that the class implements the plugin interface. That check belongs to the workflow service, which loads it in an
 * isolated class loader and is the thing that would suffer if it were wrong.
 *
 * <h2>Every problem, not the first</h2>
 *
 * <p>An author fixing a plugin should not discover the rules one rejected upload at a time.
 */
@Service
public class PluginValidationService {

    private static final Logger log = LoggerFactory.getLogger(PluginValidationService.class);

    /** A zip whose entries expand to far more than the archive is a decompression bomb, not a plugin. */
    private static final long MAX_EXPANDED_RATIO = 200;

    /** Entries above this expanded size are refused outright, whatever the ratio. */
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    private final PluginServerProperties properties;

    public PluginValidationService(PluginServerProperties properties) {
        this.properties = properties;
    }

    /**
     * What the archive turned out to contain.
     *
     * @param manifest    the parsed manifest
     * @param checksum    SHA-256 of the archive, lower-case hex
     * @param sizeBytes   archive size
     * @param entryCount  number of entries
     * @param signed      whether the archive carries signature files
     */
    public record Inspection(PluginManifest manifest, String checksum, long sizeBytes, int entryCount,
                            boolean signed) {
    }

    /**
     * Validates an archive and reports what it contains.
     *
     * @param fileName original upload name, used only in messages
     * @param content  the archive bytes
     * @return the inspection
     * @throws PluginServerException with every problem found, when the archive is not an acceptable plugin
     */
    public Inspection inspect(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw PluginServerException.invalidArchive("The uploaded file is empty.", List.of());
        }
        long limit = properties.getRegistry().getMaxJarSize().toBytes();
        if (content.length > limit) {
            throw PluginServerException.archiveTooLarge(content.length, limit);
        }
        if (fileName != null && !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            // A warning rather than a rejection: the extension is chosen by the uploader and proves nothing
            // either way. The zip structure below is what actually decides.
            log.info("Upload '{}' does not end in .jar; validating its contents anyway", fileName);
        }

        ArchiveScan scan = scan(content);
        List<String> problems = new ArrayList<>(scan.problems());

        if (scan.manifestJson() == null) {
            throw PluginServerException.invalidArchive(
                    "The archive has no " + PluginManifest.LOCATION + ", so it does not declare what plugin it "
                            + "is. Add that file to the plugin's resources.",
                    problems);
        }

        PluginManifest manifest = PluginManifest.parse(scan.manifestJson());
        problems.addAll(manifest.validate());

        // The one cross-check available without loading anything: the class the manifest names must be present.
        if (manifest.mainClass() != null && !manifest.mainClass().isBlank()) {
            String expected = manifest.mainClass().replace('.', '/') + ".class";
            if (!scan.entries().contains(expected)) {
                problems.add("The manifest names mainClass '" + manifest.mainClass()
                        + "' but the archive contains no " + expected + ".");
            }
        }

        if (!problems.isEmpty()) {
            throw PluginServerException.invalidManifest(problems);
        }

        String checksum = sha256(content);
        log.info("Accepted archive '{}' as {} ({} entries, {} bytes, sha256 {})", fileName,
                manifest.coordinate(), scan.entries().size(), content.length, checksum);
        return new Inspection(manifest, checksum, content.length, scan.entries().size(), scan.signed());
    }

    /**
     * SHA-256 of the archive, in lower-case hex.
     *
     * <p>The value a workflow service recomputes after downloading and compares before loading. It is the only
     * thing standing between a corrupted or substituted download and a class loader.
     *
     * @param content the bytes
     * @return lower-case hex digest
     */
    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM ships SHA-256. If this happens the platform is unusable, not merely unable to upload.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }

    // ------------------------------------------------------------------- internals

    private record ArchiveScan(Set<String> entries, String manifestJson, boolean signed,
                              List<String> problems) {
    }

    /**
     * Walks the archive once, reading entry names and the manifest.
     *
     * <p>{@code ZipInputStream} rather than {@code JarFile}, because the latter wants a file on disk and would
     * mean writing an untrusted upload to the filesystem before deciding whether to keep it.
     */
    private ArchiveScan scan(byte[] content) {
        Set<String> entries = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        String manifestJson = null;
        boolean signed = false;
        long expandedTotal = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                /*
                 * Separators normalised to forward slashes.
                 *
                 * The ZIP specification requires forward slashes, and every archive Maven or the jar tool
                 * produces uses them. Some Windows tooling, including .NET's ZipFile.CreateFromDirectory, writes
                 * backslashes instead, and an archive built that way plainly contains
                 * META-INF\workflow-plugin.json while matching nothing this service looks for. Refusing it with
                 * "the archive has no manifest" is technically defensible and useless to the author, who can see
                 * the file in it.
                 */
                String name = entry.getName().replace('\\', '/');

                /*
                 * A path that escapes its own archive. Harmless while nothing extracts it, and this service does
                 * not, but the workflow service will unpack these archives to a cache directory and an entry
                 * called ../../bin/java is how that becomes a write outside it. Rejected here, once, rather than
                 * relied upon to be handled correctly by every future consumer.
                 */
                if (name.contains("..") || name.startsWith("/") || name.contains(":")) {
                    problems.add("Entry '" + name + "' has a path that escapes the archive.");
                    continue;
                }
                entries.add(name);

                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    signed = true;
                }

                if (PluginManifest.LOCATION.equals(name)) {
                    byte[] bytes = zip.readAllBytes();
                    expandedTotal += bytes.length;
                    manifestJson = new String(bytes, StandardCharsets.UTF_8);
                    continue;
                }

                // Entry sizes are self-declared and can lie, so the guard uses the declared size when present
                // and skips the entry otherwise rather than decompressing everything to find out.
                long declared = entry.getSize();
                if (declared > MAX_ENTRY_BYTES) {
                    problems.add("Entry '" + name + "' declares " + declared
                            + " bytes, which is beyond anything a plugin needs.");
                }
                if (declared > 0) {
                    expandedTotal += declared;
                }
            }
        } catch (IOException ex) {
            throw PluginServerException.invalidArchive(
                    "The upload is not a readable archive: " + ex.getMessage(), List.of());
        }

        if (entries.isEmpty()) {
            problems.add("The archive contains no entries.");
        }
        if (expandedTotal > 0 && expandedTotal / Math.max(1, content.length) > MAX_EXPANDED_RATIO) {
            problems.add("The archive expands to " + expandedTotal + " bytes from " + content.length
                    + ", which is a compression ratio no plugin has a reason for.");
        }
        return new ArchiveScan(entries, manifestJson, signed, problems);
    }
}
