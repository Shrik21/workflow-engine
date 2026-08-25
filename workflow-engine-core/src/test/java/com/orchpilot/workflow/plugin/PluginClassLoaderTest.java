package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.plugin.WorkflowPlugin;
import com.orchpilot.workflow.support.TestJars;
import com.orchpilot.workflow.support.testplugin.EchoPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The isolation properties the whole plugin model depends on.
 */
class PluginClassLoaderTest {

    private static PluginClassLoader loaderFor(Path jar) throws IOException {
        return new PluginClassLoader("test:1.0.0", new URL[]{jar.toUri().toURL()},
                PluginClassLoaderTest.class.getClassLoader(), List.of());
    }

    private static Path writeJar(Path directory, byte[] content) throws IOException {
        Path jar = directory.resolve("plugin.jar");
        Files.write(jar, content);
        return jar;
    }

    @Test
    @DisplayName("plugin classes are loaded from the JAR, not from the parent, even when the parent has them")
    void loadsPluginClassesChildFirst(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        try (PluginClassLoader loader = loaderFor(jar)) {
            Class<?> loaded = loader.loadClass(EchoPlugin.class.getName());

            assertSame(loader, loaded.getClassLoader(), "child-first delegation is what isolates dependencies");
            assertNotSame(EchoPlugin.class, loaded, "the parent's copy must not win");
            assertTrue(loader.locallyLoadedClassCount() >= 1);
        }
    }

    @Test
    @DisplayName("the SDK is shared with the parent, so plugin and engine agree on the API types")
    void sharesSdkTypesWithParent() throws Exception {
        Path directory = Files.createTempDirectory("plugin-loader-test");
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        try (PluginClassLoader loader = loaderFor(jar)) {
            Class<?> sharedInterface = loader.loadClass(WorkflowNodePlugin.class.getName());
            Class<?> loadedPlugin = loader.loadClass(EchoPlugin.class.getName());

            assertSame(WorkflowNodePlugin.class, sharedInterface,
                    "a second copy of the SDK would make every engine-to-plugin call fail with ClassCastException");
            assertTrue(WorkflowPlugin.class.isAssignableFrom(loadedPlugin),
                    "the engine must be able to see the plugin as a WorkflowPlugin");
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    @DisplayName("an instance created from the JAR is usable through the shared interface")
    void instanceIsUsableThroughSharedInterface(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        try (PluginClassLoader loader = loaderFor(jar)) {
            Class<?> loaded = loader.loadClass(EchoPlugin.class.getName());
            WorkflowPlugin instance = (WorkflowPlugin) loaded.getDeclaredConstructor().newInstance();

            assertEquals("echo", instance.getId());
            assertEquals("1.0.0", instance.getVersion());
        }
    }

    @Test
    @DisplayName("JDK, SLF4J and SDK packages delegate to the parent; everything else does not")
    void parentFirstAllowlistIsCorrect(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        try (PluginClassLoader loader = loaderFor(jar)) {
            assertTrue(loader.isParentFirst("java.lang.String"));
            assertTrue(loader.isParentFirst("javax.crypto.Cipher"));
            assertTrue(loader.isParentFirst("jakarta.servlet.Filter"));
            assertTrue(loader.isParentFirst("org.slf4j.Logger"));
            assertTrue(loader.isParentFirst("com.orchpilot.workflow.sdk.node.NodeExecutionResult"));

            assertFalse(loader.isParentFirst("com.orchpilot.workflow.plugin.PluginManager"),
                    "engine internals are not shared: a plugin must not be able to reach them by accident");
            assertFalse(loader.isParentFirst("com.fasterxml.jackson.databind.ObjectMapper"),
                    "a plugin must be able to bundle its own library versions");
            assertFalse(loader.isParentFirst("org.example.Anything"));
        }
    }

    @Test
    @DisplayName("extra shared packages can be declared for deployments that intentionally share a library")
    void additionalSharedPackagesAreHonoured(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        try (PluginClassLoader loader = new PluginClassLoader("test:1.0.0",
                new URL[]{jar.toUri().toURL()}, getClass().getClassLoader(),
                List.of("com.acme.shared", "com.other.shared."))) {
            assertTrue(loader.isParentFirst("com.acme.shared.Thing"));
            assertTrue(loader.isParentFirst("com.other.shared.Thing"));
            assertFalse(loader.isParentFirst("com.acme.private.Thing"));
        }
    }

    @Test
    @DisplayName("plugin resources come before parent resources, which is what makes service discovery work")
    void pluginResourcesTakePrecedence(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory,
                TestJars.resourceOnlyJar("META-INF/test-marker.txt", "from-plugin"));

        try (PluginClassLoader loader = loaderFor(jar)) {
            URL resource = loader.getResource("META-INF/test-marker.txt");

            assertTrue(resource != null && resource.toString().contains("plugin.jar"));
            // Caching is disabled explicitly. A jar: URL connection opened with the JDK's default caching keeps a
            // JarFile in a global cache for the lifetime of the JVM, which on Windows locks the archive even after
            // the class loader is closed. Plugin authors reading their own bundled resources should do the same, and
            // the engine's workspace cleaner tolerates the case where something did not.
            java.net.URLConnection connection = resource.openConnection();
            connection.setUseCaches(false);
            try (var in = connection.getInputStream()) {
                assertEquals("from-plugin", new String(in.readAllBytes()).trim());
            }

            List<URL> all = Collections.list(loader.getResources("META-INF/test-marker.txt"));
            assertEquals(1, all.size());
        }
    }

    @Test
    @DisplayName("a class in neither the JAR nor the parent fails cleanly")
    void unknownClassFails(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        try (PluginClassLoader loader = loaderFor(jar)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.nowhere.Missing"));
        }
    }

    @Test
    @DisplayName("closing releases the JAR handle so the file can be deleted")
    void closingReleasesTheJar(@TempDir Path directory) throws Exception {
        Path jar = writeJar(directory, TestJars.pluginJar(EchoPlugin.class));

        PluginClassLoader loader = loaderFor(jar);
        loader.loadClass(EchoPlugin.class.getName());
        loader.close();

        // On Windows an open JAR keeps a file lock, so this assertion is the one that catches a leaked handle.
        assertTrue(Files.deleteIfExists(jar), "the JAR must be deletable once the loader is closed");
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort in a test.
                }
            });
        }
    }
}
