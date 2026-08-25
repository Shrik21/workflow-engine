package com.orchpilot.workflow.plugin.icon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The icons the plugins in this repository actually ship.
 *
 * <p>The sanitiser is tested against hostile input elsewhere. This guards the opposite risk: that tightening
 * the allowlist quietly strips real artwork, and nobody notices until an icon renders blank in the designer.
 *
 * <p>Everything here is discovered by walking the plugins directory rather than listed, so an icon added
 * tomorrow is covered without anybody remembering to add it to a test — which is exactly the sort of thing
 * nobody remembers.
 */
class ShippedPluginIconsTest {

    /** The plugins directory, relative to this module. Absent in a packaged build, which is fine. */
    private static Path pluginsRoot() {
        return Path.of("..", "plugins").normalize();
    }

    /** Every image directly under a plugin's {@code META-INF}, which is where the extractor looks. */
    private static List<Path> shippedIcons() throws IOException {
        Path root = pluginsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(Files::isRegularFile)
                    // Sources only. Maven copies resources into target/classes, so walking everything finds
                    // each icon twice and attributes the copy to the wrong directory depth.
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/resources/"))
                    .filter(path -> {
                        Path parent = path.getParent();
                        return parent != null && "META-INF".equals(parent.getFileName().toString());
                    })
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".svg") || name.endsWith(".png");
                    })
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("every shipped SVG survives sanitising with nothing removed")
    void svgsSurviveSanitising() throws IOException {
        List<Path> icons = shippedIcons().stream()
                .filter(path -> path.toString().toLowerCase().endsWith(".svg"))
                .toList();
        assumeTrue(!icons.isEmpty(), "not running from a source checkout");

        List<String> problems = new ArrayList<>();
        for (Path icon : icons) {
            try {
                SvgSanitizer.Result result = SvgSanitizer.sanitize(Files.readAllBytes(icon));
                String clean = new String(result.svg(), StandardCharsets.UTF_8);

                if (!result.removed().isEmpty()) {
                    problems.add(icon + " lost " + result.removed());
                }
                // A sanitiser that returns a well-formed but empty <svg/> passes every other check while
                // rendering nothing, which is the failure that would actually reach a user.
                if (!clean.matches("(?s).*<(path|rect|circle|ellipse|polygon|polyline|line|text|g)\\b.*")) {
                    problems.add(icon + " has no drawable shapes left");
                }
            } catch (IllegalArgumentException ex) {
                problems.add(icon + " was rejected outright: " + ex.getMessage());
            }
        }

        assertThat(problems)
                .withFailMessage("Shipped artwork did not survive sanitising. Either the allowlist is too "
                        + "tight or the file is not usable as a plugin icon:%n  %s",
                        String.join("%n  ", problems))
                .isEmpty();
    }

    @Test
    @DisplayName("every shipped PNG is really a PNG and within the size cap")
    void pngsAreValid() throws IOException {
        List<Path> icons = shippedIcons().stream()
                .filter(path -> path.toString().toLowerCase().endsWith(".png"))
                .toList();
        assumeTrue(!icons.isEmpty(), "no PNG icons shipped");

        List<String> problems = new ArrayList<>();
        for (Path icon : icons) {
            byte[] data = Files.readAllBytes(icon);
            // The extractor checks the magic bytes rather than trusting the extension, and skips the icon
            // when they do not match — so a mislabelled file is silently iconless rather than broken.
            boolean png = data.length > 8 && (data[0] & 0xFF) == 0x89
                    && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
            if (!png) {
                problems.add(icon + " is named .png but does not start with the PNG signature");
            }
            if (data.length > PluginIconExtractor.MAX_ICON_BYTES) {
                problems.add(icon + " is " + data.length + " bytes, over the "
                        + PluginIconExtractor.MAX_ICON_BYTES + " byte cap, and would be skipped");
            }
        }

        assertThat(problems).withFailMessage("%s", String.join("\n  ", problems)).isEmpty();
    }

    @Test
    @DisplayName("every shipped icon is within the size cap")
    void allWithinCap() throws IOException {
        List<Path> icons = shippedIcons();
        assumeTrue(!icons.isEmpty(), "not running from a source checkout");

        for (Path icon : icons) {
            assertThat(Files.size(icon))
                    .withFailMessage("%s is %d bytes; the extractor skips anything over %d",
                            icon, Files.size(icon), PluginIconExtractor.MAX_ICON_BYTES)
                    .isLessThanOrEqualTo(PluginIconExtractor.MAX_ICON_BYTES);
        }
    }

    @Test
    @DisplayName("no plugin ships two images under META-INF")
    void oneIconEach() throws IOException {
        List<Path> icons = shippedIcons();
        assumeTrue(!icons.isEmpty(), "not running from a source checkout");

        List<String> duplicates = new ArrayList<>();
        java.util.Map<String, List<Path>> byPlugin = new java.util.LinkedHashMap<>();
        for (Path icon : icons) {
            // .../<plugin>/src/main/resources/META-INF/<file> — five levels up from the file is the module.
            // Derived by walking up rather than by index, so it stays right if the path is absolute.
            Path module = icon;
            for (int i = 0; i < 5 && module.getParent() != null; i++) {
                module = module.getParent();
            }
            byPlugin.computeIfAbsent(module.getFileName().toString(), key -> new ArrayList<>()).add(icon);
        }
        byPlugin.forEach((plugin, files) -> {
            if (files.size() > 1) {
                // Not fatal — the extractor picks one and logs which — but it is almost always an
                // accident, and the one it picks is not the one somebody meant.
                duplicates.add(plugin + " ships " + files.size() + ": " + files);
            }
        });

        assertThat(duplicates)
                .withFailMessage("A plugin shipping several images gets whichever the extractor prefers. "
                        + "Delete the spare, or name the wanted one in the manifest's \"icon\":%n  %s",
                        String.join("%n  ", duplicates))
                .isEmpty();
    }
}
