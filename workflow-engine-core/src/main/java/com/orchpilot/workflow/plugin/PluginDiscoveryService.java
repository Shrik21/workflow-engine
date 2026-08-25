package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.sdk.plugin.PluginApi;
import com.orchpilot.workflow.sdk.plugin.WorkflowPlugin;
import com.orchpilot.workflow.utility.JarUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the {@link WorkflowPlugin} implementation inside a JAR.
 *
 * <p>Three mechanisms, in descending order of preference:
 *
 * <ol>
 *   <li><b>Manifest attribute</b> {@code Workflow-Plugin-Class}. Explicit, cheap to read, and impossible
 *       to get accidentally wrong. This is what the sample plugins use.</li>
 *   <li><b>{@link java.util.ServiceLoader}</b> through {@code META-INF/services}. The standard Java
 *       extension mechanism, and what a plugin built with an annotation processor will produce.</li>
 *   <li><b>Caller-declared class name</b> from the upload request. The escape hatch for a JAR that was
 *       not built with either of the above.</li>
 * </ol>
 *
 * <p>Reading the service file directly rather than running {@code ServiceLoader} is deliberate:
 * {@code ServiceLoader} instantiates providers as it iterates, which would run plugin constructor code
 * before the archive has been validated. Discovery must not execute anything.
 */
@Component
public class PluginDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(PluginDiscoveryService.class);

    private static final String SERVICE_INTERFACE = WorkflowPlugin.class.getName();

    /**
     * How a plugin class was found.
     *
     * @param className fully qualified implementation class
     * @param source    which mechanism found it, for the audit record
     */
    public record Discovery(String className, String source) {
    }

    /**
     * @param jar                 staged plugin archive
     * @param declaredMainClass   class name supplied with the upload, may be {@code null}
     * @return the discovered class, or empty when none of the three mechanisms yields one
     */
    public Optional<Discovery> discover(Path jar, String declaredMainClass) {
        Map<String, String> manifest = readManifest(jar);
        String fromManifest = manifest.get(PluginApi.MANIFEST_PLUGIN_CLASS);
        if (fromManifest != null && !fromManifest.isBlank()) {
            return Optional.of(new Discovery(fromManifest.trim(), "MANIFEST"));
        }
        try {
            List<String> providers = JarUtils.readServiceProviders(jar, SERVICE_INTERFACE);
            if (!providers.isEmpty()) {
                if (providers.size() > 1) {
                    log.info("JAR declares {} plugin providers; using the first: {}", providers.size(),
                            providers.get(0));
                }
                return Optional.of(new Discovery(providers.get(0), "SERVICE_LOADER"));
            }
        } catch (IOException ex) {
            log.warn("Could not read service declarations from {}: {}", jar.getFileName(), ex.getMessage());
        }
        if (declaredMainClass != null && !declaredMainClass.isBlank()) {
            return Optional.of(new Discovery(declaredMainClass.trim(), "UPLOAD_METADATA"));
        }
        return Optional.empty();
    }

    /**
     * @param jar staged plugin archive
     * @return the API version declared in the manifest, or empty when absent or unparseable
     */
    public Optional<Integer> declaredApiVersion(Path jar) {
        String raw = readManifest(jar).get(PluginApi.MANIFEST_API_VERSION);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            log.warn("Manifest attribute {} is not a number: '{}'", PluginApi.MANIFEST_API_VERSION, raw);
            return Optional.empty();
        }
    }

    /**
     * @param jar staged plugin archive
     * @return main manifest attributes, empty when there is no manifest
     */
    public Map<String, String> readManifest(Path jar) {
        try {
            return JarUtils.readManifestAttributes(jar);
        } catch (IOException ex) {
            log.warn("Could not read the manifest of {}: {}", jar.getFileName(), ex.getMessage());
            return Map.of();
        }
    }
}
