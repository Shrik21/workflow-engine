package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.plugin.PluginApi;
import com.orchpilot.workflow.sdk.plugin.WorkflowPlugin;
import com.orchpilot.workflow.utility.HashUtils;
import com.orchpilot.workflow.utility.JarUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rejects bad plugin archives before anything in them is executed.
 *
 * <p>An uploaded JAR is attacker-controlled input that is about to become code in this JVM, so validation
 * is deliberately strict and reports every problem at once. Checks, in order:
 *
 * <ol>
 *   <li>size, entry count and total uncompressed size, which is the anti zip-bomb bound;</li>
 *   <li>readable ZIP structure and no path-traversal entries;</li>
 *   <li>checksum, when the uploader declared one, or unconditionally when the engine requires it;</li>
 *   <li>signature presence, when the engine requires signed plugins;</li>
 *   <li>a discoverable plugin class that actually exists in the archive;</li>
 *   <li>that the class implements {@link WorkflowPlugin}, is public and concrete, and has a public
 *       no-argument constructor;</li>
 *   <li>a supported plugin API version;</li>
 *   <li>no duplicate {@code pluginId:version} already installed.</li>
 * </ol>
 *
 * <p>Point six loads the class but never instantiates it, in a throwaway class loader that is closed
 * immediately. Loading a class runs its static initialiser, which is already a small amount of trust; the
 * alternative, parsing the class file by hand to check its interface list, is not worth the complexity for
 * an endpoint that is already behind an administrative key.
 */
@Component
public class PluginJarValidator {

    private static final Logger log = LoggerFactory.getLogger(PluginJarValidator.class);

    private final WorkflowEngineProperties properties;
    private final PluginDiscoveryService discoveryService;
    private final PluginVersionRepository versionRepository;

    public PluginJarValidator(WorkflowEngineProperties properties, PluginDiscoveryService discoveryService,
                              PluginVersionRepository versionRepository) {
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.versionRepository = versionRepository;
    }

    /**
     * What validation established about an archive.
     *
     * @param className   the plugin implementation class
     * @param source      how the class was discovered
     * @param sha256      checksum of the uploaded bytes
     * @param signed      whether the archive carries signature files
     * @param apiVersion  API version declared in the manifest, or the current one when absent
     * @param entryCount  number of archive entries
     * @param libraries   bundled library JARs found under {@code lib/}
     */
    public record ValidationResult(String className, String source, String sha256, boolean signed,
                                   int apiVersion, int entryCount, List<String> libraries) {
    }

    /**
     * Validates an uploaded archive.
     *
     * @param stagedJar  the archive written to a temporary location
     * @param content    the uploaded bytes, for checksum verification
     * @param request    operator-supplied upload metadata
     * @return what validation established
     * @throws PluginValidationException listing every problem found
     */
    public ValidationResult validate(Path stagedJar, byte[] content, PluginUploadRequest request) {
        List<String> errors = new ArrayList<>();
        WorkflowEngineProperties.Plugins config = properties.getPlugins();

        if (content == null || content.length == 0) {
            throw new PluginValidationException("The uploaded file is empty");
        }
        if (content.length > config.getMaxJarBytes()) {
            throw new PluginValidationException("The JAR is " + content.length + " bytes, which exceeds the "
                    + config.getMaxJarBytes() + " byte limit");
        }

        JarUtils.ArchiveSummary summary;
        try {
            summary = JarUtils.summarize(stagedJar, config.getMaxJarEntries(),
                    config.getMaxUncompressedBytes());
        } catch (IOException ex) {
            throw new PluginValidationException("The file is not a readable JAR: " + ex.getMessage());
        }

        String sha256 = HashUtils.sha256Hex(content);
        if (request.expectedSha256() != null && !request.expectedSha256().isBlank()
                && !request.expectedSha256().equalsIgnoreCase(sha256)) {
            errors.add("Checksum mismatch: the uploader expected " + request.expectedSha256()
                    + " but the received bytes hash to " + sha256);
        } else if (config.isRequireChecksum()
                && (request.expectedSha256() == null || request.expectedSha256().isBlank())) {
            errors.add("This engine requires an expectedSha256 with every plugin upload");
        }

        if (config.isRequireSignature() && !summary.signed()) {
            errors.add("This engine requires signed plugin JARs but the archive contains no signature");
        }
        if (!summary.hasManifest()) {
            log.info("Plugin archive has no manifest; falling back to service or declared class discovery");
        }

        String className = null;
        String source = null;
        var discovered = discoveryService.discover(stagedJar, request.mainClass());
        if (discovered.isEmpty()) {
            errors.add("No plugin class could be found. Add a " + PluginApi.MANIFEST_PLUGIN_CLASS
                    + " manifest attribute, a META-INF/services/" + WorkflowPlugin.class.getName()
                    + " entry, or supply mainClass with the upload.");
        } else {
            className = discovered.get().className();
            source = discovered.get().source();
            try {
                if (!JarUtils.containsClass(stagedJar, className)) {
                    errors.add("Declared plugin class '" + className + "' is not present in the archive");
                    className = null;
                }
            } catch (IOException ex) {
                errors.add("Could not inspect the archive for '" + className + "': " + ex.getMessage());
                className = null;
            }
        }

        int apiVersion = discoveryService.declaredApiVersion(stagedJar).orElse(PluginApi.VERSION);
        if (apiVersion < PluginApi.MINIMUM_SUPPORTED_VERSION || apiVersion > PluginApi.VERSION) {
            errors.add("Plugin API version " + apiVersion + " is not supported by this engine, which "
                    + "supports " + PluginApi.MINIMUM_SUPPORTED_VERSION + " to " + PluginApi.VERSION);
        }

        if (className != null) {
            errors.addAll(verifyClassContract(stagedJar, className, summary.bundledLibraries()));
        }

        if (request.pluginId() != null && !request.pluginId().isBlank()
                && request.version() != null && !request.version().isBlank()
                && versionRepository.existsByPluginIdAndVersion(request.pluginId(), request.version())) {
            errors.add("Version '" + request.version() + "' of plugin '" + request.pluginId()
                    + "' is already installed. Upload a new version, or delete that one first.");
        }

        if (!errors.isEmpty()) {
            throw new PluginValidationException(errors);
        }
        log.info("Plugin archive validated: class={} via {} sha256={} entries={} libs={} signed={}",
                className, source, sha256, summary.entryCount(), summary.bundledLibraries().size(),
                summary.signed());
        return new ValidationResult(className, source, sha256, summary.signed(), apiVersion,
                summary.entryCount(), summary.bundledLibraries());
    }

    /**
     * Loads the class in a throwaway loader to check it can actually be used as a plugin.
     *
     * <p>Catching this here rather than at load time turns a confusing runtime failure, halfway through
     * registering a plugin, into a clear rejection on the upload response.
     */
    private List<String> verifyClassContract(Path stagedJar, String className, List<String> libraries) {
        List<String> errors = new ArrayList<>();
        Path libDirectory = stagedJar.getParent().resolve("validate-lib");
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(stagedJar.toUri().toURL());
            if (!libraries.isEmpty()) {
                for (Path extracted : JarUtils.extractBundledLibraries(stagedJar, libDirectory)) {
                    urls.add(extracted.toUri().toURL());
                }
            }
        } catch (IOException ex) {
            errors.add("Could not prepare the archive for inspection: " + ex.getMessage());
            return errors;
        }

        try (PluginClassLoader probe = new PluginClassLoader("validate:" + className,
                urls.toArray(new URL[0]), getClass().getClassLoader(), List.of())) {
            Class<?> type = probe.loadClass(className);
            if (!WorkflowPlugin.class.isAssignableFrom(type)) {
                errors.add("Class '" + className + "' does not implement " + WorkflowPlugin.class.getName());
                return errors;
            }
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                errors.add("Class '" + className + "' is abstract and cannot be instantiated");
            }
            if (!Modifier.isPublic(type.getModifiers())) {
                errors.add("Class '" + className + "' must be public");
            }
            try {
                Constructor<?> constructor = type.getDeclaredConstructor();
                if (!Modifier.isPublic(constructor.getModifiers())) {
                    errors.add("Class '" + className + "' must have a public no-argument constructor");
                }
            } catch (NoSuchMethodException ex) {
                errors.add("Class '" + className + "' has no no-argument constructor");
            }
        } catch (ClassNotFoundException ex) {
            errors.add("Class '" + className + "' could not be loaded: " + ex.getMessage());
        } catch (NoClassDefFoundError ex) {
            errors.add("Class '" + className + "' references a missing dependency: " + ex.getMessage()
                    + ". Bundle it under lib/ inside the plugin JAR.");
        } catch (IOException ex) {
            errors.add("Could not inspect '" + className + "': " + ex.getMessage());
        } catch (LinkageError ex) {
            errors.add("Class '" + className + "' failed to link: " + ex.getMessage());
        } finally {
            deleteQuietly(libDirectory);
        }
        return errors;
    }

    private static void deleteQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: the workspace cleaner removes what is left.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
