package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.exception.PluginLoadException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Local staging area for plugin JARs and their scratch space.
 *
 * <p>A {@code URLClassLoader} needs URLs it can open repeatedly, so the JAR has to exist on disk even
 * though the authoritative copy lives in GridFS.
 *
 * <p>Every version gets its own directory, including a nonce. That is a concession to Windows, where an
 * open JAR keeps a file lock and deleting it immediately after {@code close()} sometimes fails; reusing
 * the path on the next load would then fail too. A fresh directory per load makes reload reliable, and the
 * stale directory is cleaned up on the next start.
 */
@Component
public class PluginWorkspace {

    private static final Logger log = LoggerFactory.getLogger(PluginWorkspace.class);

    private final Path root;

    public PluginWorkspace(WorkflowEngineProperties properties) {
        String configured = properties.getPlugins().getWorkspaceDirectory();
        try {
            this.root = (configured == null || configured.isBlank())
                    ? Files.createTempDirectory("workflow-plugins-")
                    : Files.createDirectories(Path.of(configured));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create the plugin workspace directory", ex);
        }
        log.info("Plugin workspace root: {}", root.toAbsolutePath());
        purgeStaleDirectories();
    }

    /** @return the workspace root */
    public Path root() {
        return root;
    }

    /**
     * Creates a fresh directory for one load of one version.
     *
     * @param pluginId plugin id
     * @param version  plugin version
     * @return an existing, writable directory
     */
    public Path allocate(String pluginId, String version) {
        String safe = sanitize(pluginId) + "-" + sanitize(version) + "-"
                + Long.toHexString(System.nanoTime());
        Path directory = root.resolve(safe);
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException ex) {
            throw new PluginLoadException(pluginId + ":" + version,
                    "could not create a workspace directory", ex);
        }
    }

    /**
     * Best-effort recursive delete.
     *
     * <p>Failure is logged, not thrown. A directory that cannot be removed because the operating system
     * still holds a lock is a few megabytes of disk, whereas failing the unload would leave the plugin
     * half-removed and the registry inconsistent. The next start clears it.
     *
     * @param directory directory to remove
     */
    public void release(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        if (!directory.normalize().startsWith(root.normalize())) {
            log.warn("Refusing to delete {}: outside the plugin workspace root", directory);
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.debug("Could not delete {} yet: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.debug("Could not walk {} for deletion: {}", directory, ex.getMessage());
        }
        if (Files.exists(directory)) {
            log.info("Plugin workspace {} could not be removed now; it will be cleaned up on the next start",
                    directory.getFileName());
        }
    }

    /**
     * Removes directories left behind by a previous run.
     */
    private void purgeStaleDirectories() {
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(this::release);
        } catch (IOException ex) {
            log.debug("Could not list the plugin workspace for cleanup: {}", ex.getMessage());
        }
    }

    @PreDestroy
    void cleanUp() {
        purgeStaleDirectories();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }
}
