package com.orchpilot.workflow.utility;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Read-only inspection of plugin JAR archives.
 *
 * <p>Everything here runs before any plugin class is loaded, which is the only point at which the
 * engine can still refuse an archive cheaply. Note the deliberate bounds on entry count and
 * uncompressed size: a plugin JAR is attacker-controlled input, and an unbounded extraction is a
 * denial-of-service primitive.
 */
public final class JarUtils {

    private JarUtils() {
    }

    /** Summary of a JAR's shape, gathered in a single pass. */
    public record ArchiveSummary(int entryCount, long uncompressedBytes, boolean hasManifest,
                                 boolean signed, List<String> bundledLibraries) {
    }

    /**
     * Walks the archive once, collecting the facts validation needs and enforcing hard bounds.
     *
     * @param jar                  archive to inspect
     * @param maxEntries           reject archives with more entries than this
     * @param maxUncompressedBytes reject archives whose entries sum to more than this
     * @return a summary of the archive
     * @throws IOException when the file is not a readable ZIP or exceeds a bound
     */
    public static ArchiveSummary summarize(Path jar, int maxEntries, long maxUncompressedBytes) throws IOException {
        int count = 0;
        long total = 0;
        boolean signed = false;
        boolean hasManifest = false;
        List<String> libraries = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                count++;
                if (count > maxEntries) {
                    throw new IOException("Archive contains more than " + maxEntries + " entries");
                }
                String name = entry.getName();
                if (name.contains("..")) {
                    throw new IOException("Archive contains a path traversal entry: " + name);
                }
                long size = entry.getSize();
                if (size > 0) {
                    total += size;
                    if (total > maxUncompressedBytes) {
                        throw new IOException("Archive expands to more than " + maxUncompressedBytes + " bytes");
                    }
                }
                if (JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) {
                    hasManifest = true;
                } else if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    signed = true;
                } else if (isBundledLibrary(name)) {
                    libraries.add(name);
                }
            }
        }
        return new ArchiveSummary(count, total, hasManifest, signed, List.copyOf(libraries));
    }

    /**
     * @param jar archive to read
     * @return main manifest attributes as a name/value map, empty when there is no manifest
     * @throws IOException when the archive cannot be read
     */
    public static Map<String, String> readManifestAttributes(Path jar) throws IOException {
        Map<String, String> attributes = new LinkedHashMap<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return attributes;
            }
            Attributes main = manifest.getMainAttributes();
            for (Object key : main.keySet()) {
                attributes.put(String.valueOf(key), main.getValue(String.valueOf(key)));
            }
        }
        return attributes;
    }

    /**
     * @param jar       archive to read
     * @param className fully qualified class name
     * @return {@code true} when the archive contains a class file for {@code className}
     * @throws IOException when the archive cannot be read
     */
    public static boolean containsClass(Path jar, String className) throws IOException {
        if (className == null || className.isBlank()) {
            return false;
        }
        String entryName = className.replace('.', '/') + ".class";
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getEntry(entryName) != null;
        }
    }

    /**
     * @param jar archive to read
     * @return service-provider class names declared for {@code WorkflowPlugin}, in file order
     * @throws IOException when the archive cannot be read
     */
    public static List<String> readServiceProviders(Path jar, String serviceInterface) throws IOException {
        List<String> providers = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry("META-INF/services/" + serviceInterface);
            if (entry == null) {
                return providers;
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String rawLine : content.split("\\R")) {
                    String line = rawLine;
                    int comment = line.indexOf('#');
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    line = line.trim();
                    if (!line.isEmpty()) {
                        providers.add(line);
                    }
                }
            }
        }
        return providers;
    }

    /**
     * Extracts JARs bundled inside a plugin archive so they can join its class path.
     *
     * <p>A plugin may ship its own dependencies under {@code lib/} or {@code BOOT-INF/lib/}. Because
     * the plugin class loader is child-first, those copies win for the plugin and stay invisible to
     * the engine, which is how two plugins can use incompatible versions of the same library.
     *
     * @param jar       plugin archive
     * @param targetDir directory to extract into; created when absent
     * @return paths of the extracted library JARs, in archive order
     * @throws IOException when extraction fails
     */
    public static List<Path> extractBundledLibraries(Path jar, Path targetDir) throws IOException {
        List<Path> extracted = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isBundledLibrary(entry.getName())) {
                    continue;
                }
                String fileName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                if (fileName.isEmpty() || fileName.contains("..")) {
                    continue;
                }
                Files.createDirectories(targetDir);
                Path target = targetDir.resolve(fileName);
                if (!target.normalize().startsWith(targetDir.normalize())) {
                    throw new IOException("Refusing to extract outside the workspace: " + entry.getName());
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                extracted.add(target);
            }
        }
        return extracted;
    }

    private static boolean isBundledLibrary(String entryName) {
        return entryName.endsWith(".jar")
                && (entryName.startsWith("lib/") || entryName.startsWith("BOOT-INF/lib/"));
    }
}
