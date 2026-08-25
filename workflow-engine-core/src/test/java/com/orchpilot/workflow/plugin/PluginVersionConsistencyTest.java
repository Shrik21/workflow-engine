package com.orchpilot.workflow.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every plugin's POM version and its compiled {@code PLUGIN_VERSION} constant must agree.
 *
 * <h2>Why this is worth a test</h2>
 *
 * The two are read by different things. Maven filters the POM version into {@code workflow-plugin.json}, which
 * is what an upload declares; {@code DefaultPluginManager.probe()} instantiates the plugin and reads
 * {@code getVersion()}, which returns the constant. When they disagree the engine refuses the archive with
 * <em>"the upload declares version X but the archive reports Y"</em> — a message that points at the upload
 * rather than at the two files that actually drifted, and which is only ever seen after a full build and a
 * manual upload.
 *
 * <p>Nothing else notices. Both files compile, both are internally consistent, and every test passes. This is
 * the only place the pair is checked, and it costs a directory walk.
 */
class PluginVersionConsistencyTest {

    /** Matches this module's own version element, not the parent's: the parent block comes first. */
    private static final Pattern OWN_VERSION = Pattern.compile(
            "<artifactId>([a-z0-9-]+)</artifactId>.*?<version>([^<]+)</version>", Pattern.DOTALL);

    private static final Pattern CONSTANT = Pattern.compile("PLUGIN_VERSION\\s*=\\s*\"([^\"]+)\"");

    private static Path pluginsRoot() {
        return Path.of("..", "plugins").normalize();
    }

    @Test
    @DisplayName("every plugin's POM version matches its PLUGIN_VERSION constant")
    void versionsAgree() throws IOException {
        Path root = pluginsRoot();
        assumeTrue(Files.isDirectory(root), "not running from a source checkout");

        List<String> problems = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path pom = module.resolve("pom.xml");
                Path sources = module.resolve("src/main/java");
                if (!Files.exists(pom) || !Files.isDirectory(sources)) {
                    continue;
                }
                String name = module.getFileName().toString();

                String declared = pomVersion(pom, name);
                String compiled = constantVersion(sources);

                if (compiled == null) {
                    // A plugin with no constant cannot be probed at all, which is a different bug.
                    problems.add(name + " has no PLUGIN_VERSION constant");
                    continue;
                }
                if (declared == null) {
                    // Inheriting the reactor's version silently ties the plugin's identity to the engine's,
                    // so the constant and the manifest drift apart the moment either moves.
                    problems.add(name + " has no <version> of its own; its manifest would inherit the "
                            + "reactor's while PLUGIN_VERSION says " + compiled);
                    continue;
                }
                if (!declared.equals(compiled)) {
                    problems.add(name + ": pom.xml says " + declared + " but PLUGIN_VERSION says " + compiled);
                }
            }
        }

        assertThat(problems)
                .withFailMessage("Plugin versions have drifted. An upload of any of these is refused by the "
                        + "engine with a message about the archive, not about these files:%n  %s",
                        String.join("%n  ", problems))
                .isEmpty();
    }

    /** @return the module's own version, or null when it only inherits the parent's */
    private static String pomVersion(Path pom, String moduleName) throws IOException {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        // Trimmed to what follows the parent block, so the parent's own version is never mistaken for this
        // module's — they are the same two tags in the same order.
        int afterParent = xml.indexOf("</parent>");
        String body = afterParent < 0 ? xml : xml.substring(afterParent);

        Matcher matcher = OWN_VERSION.matcher(body);
        while (matcher.find()) {
            if (moduleName.equals(matcher.group(1))) {
                return matcher.group(2).trim();
            }
        }
        return null;
    }

    private static String constantVersion(Path sources) throws IOException {
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = CONSTANT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }
}
