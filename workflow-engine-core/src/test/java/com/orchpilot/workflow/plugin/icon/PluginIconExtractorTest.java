package com.orchpilot.workflow.plugin.icon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding a plugin's icon inside a real archive.
 *
 * <p>Builds actual JARs rather than mocking the reader: the thing most likely to be wrong here is a path
 * comparison, and a mock would agree with whatever the code does.
 */
class PluginIconExtractorTest {

    private final PluginIconExtractor extractor = new PluginIconExtractor();

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24px\" "
            + "height=\"24px\" viewBox=\"0 0 24 24\"><rect width=\"24\" height=\"24\" fill=\"#4285f4\"/></svg>";

    /** A minimal valid PNG: signature plus enough bytes to be recognisable. */
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R' };

    private static Path jar(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, byte[]> entries(Object... pairs) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            map.put(String.valueOf(pairs[i]),
                    value instanceof byte[] bytes ? bytes
                            : String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    // ------------------------------------------------------------------ convention

    @Test
    @DisplayName("finds an SVG dropped straight into META-INF, spaces in the name and all")
    void findsByConvention(@TempDir Path dir) throws IOException {
        // Exactly what the GCP Compute Instance plugin ships: "META-INF/Compute Engine.svg".
        Path archive = jar(dir, "p.jar", entries(
                "META-INF/workflow-plugin.json", "{}",
                "META-INF/Compute Engine.svg", SVG,
                "com/example/Plugin.class", "x"));

        Optional<PluginIcon> icon = extractor.extract(archive, null);

        assertThat(icon).isPresent();
        assertThat(icon.get().fileName()).isEqualTo("META-INF/Compute Engine.svg");
        assertThat(icon.get().mediaType()).isEqualTo("image/svg+xml");
        assertThat(icon.get().toDataUrl()).startsWith("data:image/svg+xml;base64,");
    }

    @Test
    @DisplayName("finds a PNG too")
    void findsPng(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.png", PNG));

        assertThat(extractor.extract(archive, null))
                .get()
                .extracting(PluginIcon::mediaType)
                .isEqualTo("image/png");
    }

    @Test
    @DisplayName("prefers SVG when the archive carries both")
    void prefersSvg(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.png", PNG, "META-INF/icon.svg", SVG));

        // Vector scales to the palette's 30px and the canvas's 40px from one file.
        assertThat(extractor.extract(archive, null))
                .get()
                .extracting(PluginIcon::mediaType)
                .isEqualTo("image/svg+xml");
    }

    @Test
    @DisplayName("ignores images nested below META-INF, which are resources rather than identity")
    void ignoresNested(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries(
                "META-INF/resources/screenshot.svg", SVG,
                "assets/logo.svg", SVG));

        assertThat(extractor.extract(archive, null)).isEmpty();
    }

    @Test
    @DisplayName("an archive with no image yields nothing rather than failing")
    void noIcon(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries("META-INF/workflow-plugin.json", "{}"));

        assertThat(extractor.extract(archive, null)).isEmpty();
    }

    // ------------------------------------------------------------------ declared path

    @Test
    @DisplayName("a declared path wins over the convention")
    void declaredWins(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries(
                "META-INF/aaa-first-alphabetically.svg", SVG,
                "META-INF/chosen.svg", SVG));

        assertThat(extractor.extract(archive, "META-INF/chosen.svg"))
                .get()
                .extracting(PluginIcon::fileName)
                .isEqualTo("META-INF/chosen.svg");
    }

    @Test
    @DisplayName("a declared path that is not in the archive yields nothing")
    void declaredMissing(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.svg", SVG));

        // Deliberately does not silently fall back: the author named a file, and quietly using a different
        // one would hide the typo.
        assertThat(extractor.extract(archive, "META-INF/nope.svg")).isEmpty();
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("sanitises on the way in, so what is stored is already safe")
    void sanitisesOnIngest(@TempDir Path dir) throws IOException {
        String hostile = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">"
                + "<script>alert(1)</script><rect width=\"24\" height=\"24\"/></svg>";
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.svg", hostile));

        Optional<PluginIcon> icon = extractor.extract(archive, null);

        assertThat(icon).isPresent();
        String stored = new String(icon.get().data(), StandardCharsets.UTF_8);
        assertThat(stored).doesNotContain("script").doesNotContain("alert");
        assertThat(stored).contains("<rect");
    }

    @Test
    @DisplayName("an unparseable SVG is skipped rather than failing the install")
    void skipsBrokenSvg(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.svg", "<svg><unclosed></svg>"));

        // An icon is decoration; refusing a working plugin over a picture is the worse outcome.
        assertThat(extractor.extract(archive, null)).isEmpty();
    }

    @Test
    @DisplayName("a file named .png that is not a PNG is skipped")
    void skipsFakePng(@TempDir Path dir) throws IOException {
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.png", "<svg>not really a png</svg>"));

        assertThat(extractor.extract(archive, null)).isEmpty();
    }

    @Test
    @DisplayName("an oversized icon is skipped")
    void skipsOversized(@TempDir Path dir) throws IOException {
        String padding = "<!--" + "x".repeat(PluginIconExtractor.MAX_ICON_BYTES) + "-->";
        Path archive = jar(dir, "p.jar", entries("META-INF/icon.svg",
                SVG.replace("</svg>", padding + "</svg>")));

        // These land in the plugin's Mongo document and go to every browser that opens the designer.
        assertThat(extractor.extract(archive, null)).isEmpty();
    }

    @Test
    @DisplayName("a missing archive yields nothing rather than throwing")
    void missingArchive(@TempDir Path dir) {
        assertThat(extractor.extract(dir.resolve("absent.jar"), null)).isEmpty();
    }
}
