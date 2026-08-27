package com.orchpilot.workflow.plugin.icon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds the icon a plugin ships inside its archive.
 *
 * <h2>Two ways to declare one, and why both</h2>
 *
 * <ul>
 *   <li><b>Explicitly</b>, with {@code "icon": "META-INF/whatever.svg"} in the plugin manifest. Unambiguous,
 *       and the only way to choose when an archive carries more than one image.</li>
 *   <li><b>By convention</b>, as the single image directly under {@code META-INF/}. This exists because
 *       dropping a file into the resources directory is what a plugin author actually does first, and making
 *       that work costs one directory listing.</li>
 * </ul>
 *
 * <p>The manifest wins when both are present. Where the convention finds several candidates it prefers SVG
 * over PNG and then the shortest name, and logs which it chose — silently picking one of several images and
 * never saying so is how an author ends up staring at the wrong logo.
 *
 * <h2>What is refused</h2>
 *
 * Anything over {@link #MAX_ICON_BYTES}, anything that is not SVG or PNG, and any SVG that will not parse.
 * An icon is decoration: a plugin that ships a broken one installs fine without it, because failing an
 * install over a picture would be a worse outcome than a missing picture.
 */
@Component
public class PluginIconExtractor {

    private static final Logger log = LoggerFactory.getLogger(PluginIconExtractor.class);

    /**
     * Cap on a stored icon.
     *
     * <p>These land in the plugin's MongoDB document, which is capped at 16 MB, and are sent to every browser
     * that opens the designer. 128 KB is generous for a product mark and small enough that a plugin cannot
     * bloat the catalogue.
     */
    public static final int MAX_ICON_BYTES = 128 * 1024;

    private static final String SVG = "image/svg+xml";
    private static final String PNG = "image/png";

    /**
     * @param jar          the staged archive
     * @param declaredPath the manifest's icon path, or null
     * @return the icon, or empty when the archive ships none usable
     */
    public Optional<PluginIcon> extract(Path jar, String declaredPath) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = declaredPath == null || declaredPath.isBlank()
                    ? findByConvention(archive)
                    : archive.getJarEntry(declaredPath.trim());

            if (entry == null) {
                if (declaredPath != null && !declaredPath.isBlank()) {
                    log.warn("Plugin manifest declares icon '{}' but the archive has no such entry",
                            declaredPath);
                }
                return Optional.empty();
            }
            return read(archive, entry);
        } catch (IOException ex) {
            log.warn("Could not read an icon from {}: {}", jar.getFileName(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** The single image directly under META-INF, preferring SVG and then the shortest name. */
    private JarEntry findByConvention(JarFile archive) {
        List<JarEntry> candidates = new ArrayList<>();
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            // Directly under META-INF only: a nested path is a resource the plugin uses, not its identity.
            if (!name.regionMatches(true, 0, "META-INF/", 0, 9)
                    || name.indexOf('/', 9) >= 0) {
                continue;
            }
            if (mediaTypeOf(name) != null) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator
                .comparing((JarEntry e) -> SVG.equals(mediaTypeOf(e.getName())) ? 0 : 1)
                .thenComparing(e -> e.getName().length())
                .thenComparing(JarEntry::getName));

        if (candidates.size() > 1) {
            log.info("Plugin archive carries {} images under META-INF; using '{}'. Declare \"icon\" in the "
                    + "manifest to choose explicitly.", candidates.size(), candidates.get(0).getName());
        }
        return candidates.get(0);
    }

    private Optional<PluginIcon> read(JarFile archive, JarEntry entry) throws IOException {
        String mediaType = mediaTypeOf(entry.getName());
        if (mediaType == null) {
            log.warn("Plugin icon '{}' is not an SVG or PNG and was ignored", entry.getName());
            return Optional.empty();
        }
        if (entry.getSize() > MAX_ICON_BYTES) {
            log.warn("Plugin icon '{}' is {} bytes, over the {} byte limit, and was ignored",
                    entry.getName(), entry.getSize(), MAX_ICON_BYTES);
            return Optional.empty();
        }

        byte[] raw;
        try (InputStream stream = archive.getInputStream(entry)) {
            // Bounded by the cap rather than trusting the declared size, which a crafted archive can understate.
            raw = stream.readNBytes(MAX_ICON_BYTES + 1);
        }
        if (raw.length > MAX_ICON_BYTES) {
            log.warn("Plugin icon '{}' exceeds the {} byte limit and was ignored",
                    entry.getName(), MAX_ICON_BYTES);
            return Optional.empty();
        }
        if (raw.length == 0) {
            return Optional.empty();
        }

        if (PNG.equals(mediaType)) {
            if (!looksLikePng(raw)) {
                log.warn("Plugin icon '{}' is named .png but is not a PNG and was ignored", entry.getName());
                return Optional.empty();
            }
            return Optional.of(new PluginIcon(entry.getName(), PNG, raw));
        }

        try {
            SvgSanitizer.Result sanitised = SvgSanitizer.sanitize(raw);
            if (!sanitised.isClean()) {
                // Worth a log line at info: it is the plugin author's mistake to fix, and silently altering
                // somebody's artwork without saying so is how "the icon looks wrong" becomes unexplainable.
                log.info("Sanitised plugin icon '{}'; removed: {}", entry.getName(),
                        String.join(", ", sanitised.removed()));
            }
            return Optional.of(new PluginIcon(entry.getName(), SVG, sanitised.svg()));
        } catch (IllegalArgumentException ex) {
            log.warn("Plugin icon '{}' could not be sanitised and was ignored: {}",
                    entry.getName(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** @return the media type for a file name, or null when it is not an image we accept */
    private static String mediaTypeOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".svg")) {
            return SVG;
        }
        if (lower.endsWith(".png")) {
            return PNG;
        }
        return null;
    }

    private static boolean looksLikePng(byte[] data) {
        return data.length > 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }
}
