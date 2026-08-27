package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.event.PluginLifecycleEvent;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.exception.PluginLoadException;
import com.orchpilot.workflow.exception.PluginNotFoundException;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.model.NodeDefinitionRecord;
import com.orchpilot.workflow.model.PluginMetadata;
import com.orchpilot.workflow.model.PluginPermissions;
import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.plugin.context.CoreTriggerContext;
import com.orchpilot.workflow.plugin.context.DefaultPluginContext;
import com.orchpilot.workflow.plugin.context.PluginContextFactory;
import com.orchpilot.workflow.repository.PluginMetadataRepository;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.TriggerPlugin;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.plugin.WorkflowPlugin;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.utility.JarUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The plugin lifecycle implementation.
 *
 * <p><b>Install order matters.</b> Validate, probe, store, persist, load, register. Nothing becomes visible
 * to workflows until the last step, so a plugin that fails to initialise leaves no half-registered node
 * types behind. Conversely the bytes are stored before loading, so a plugin that fails can be inspected and
 * retried rather than having to be uploaded again.
 *
 * <p><b>Why the probe exists.</b> A plugin's own id and version are the authoritative ones, and they are
 * only knowable by instantiating it. The probe does that in a throwaway class loader, reads the identity
 * strings, and discards everything, so an archive that lies about its identity is rejected before its bytes
 * are stored under the wrong coordinate.
 *
 * <p><b>Unload order matters more.</b> Stop admitting work, wait for in-flight work, unregister, destroy,
 * close the loader, drop references. Doing any of these out of order either leaks a class loader or breaks a
 * running execution.
 */
@Service
public class DefaultPluginManager implements PluginManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultPluginManager.class);

    private final PluginMetadataRepository metadataRepository;
    private final PluginVersionRepository versionRepository;
    private final PluginJarStorage jarStorage;
    private final PluginJarValidator validator;
    private final PluginDiscoveryService discoveryService;
    private final PluginWorkspace workspace;
    private final PluginRegistry registry;
    private final PluginContextFactory contextFactory;
    private final WorkflowEventPublisher eventPublisher;
    private final AuditService auditService;
    private final WorkflowEngineProperties properties;
    private final com.orchpilot.workflow.plugin.icon.PluginIconExtractor iconExtractor;

    /** One lock per plugin id, so lifecycle operations on different plugins do not serialise. */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Trigger contexts, kept so a trigger can be told to wind down before it is stopped. */
    private final ConcurrentHashMap<String, CoreTriggerContext> triggerContexts = new ConcurrentHashMap<>();

    public DefaultPluginManager(PluginMetadataRepository metadataRepository,
                                PluginVersionRepository versionRepository, PluginJarStorage jarStorage,
                                PluginJarValidator validator, PluginDiscoveryService discoveryService,
                                PluginWorkspace workspace, PluginRegistry registry,
                                PluginContextFactory contextFactory, WorkflowEventPublisher eventPublisher,
                                AuditService auditService, WorkflowEngineProperties properties,
                                com.orchpilot.workflow.plugin.icon.PluginIconExtractor iconExtractor) {
        this.metadataRepository = metadataRepository;
        this.versionRepository = versionRepository;
        this.jarStorage = jarStorage;
        this.validator = validator;
        this.discoveryService = discoveryService;
        this.workspace = workspace;
        this.registry = registry;
        this.contextFactory = contextFactory;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.properties = properties;
        this.iconExtractor = iconExtractor;
    }

    // ----------------------------------------------------------------- install

    @Override
    public PluginVersion install(String fileName, byte[] content, PluginUploadRequest request) {
        Path stagingDirectory = workspace.allocate("upload", String.valueOf(System.nanoTime()));
        Path stagedJar = stagingDirectory.resolve(safeFileName(fileName));
        try {
            Files.write(stagedJar, content);
            PluginJarValidator.ValidationResult validation = validator.validate(stagedJar, content, request);
            ProbeResult probe = probe(stagedJar, validation.className(), validation.libraries());

            String pluginId = probe.id();
            String version = probe.version();
            verifyDeclared(request, pluginId, version);

            if (versionRepository.existsByPluginIdAndVersion(pluginId, version)) {
                throw new PluginValidationException("Version '" + version + "' of plugin '" + pluginId
                        + "' is already installed. Delete it first, or upload a different version.");
            }

            PluginJarStorage.StoredJar stored = jarStorage.store(pluginId, version,
                    fileName == null ? pluginId + ".jar" : fileName, content);

            PluginVersion versionDocument = buildVersionDocument(request, validation, probe, fileName, stored);
            applyIcon(versionDocument, stagedJar);
            versionRepository.save(versionDocument);
            upsertPluginHead(versionDocument, request.actor());

            auditService.record(request.actor(), "PLUGIN_INSTALLED", "PLUGIN",
                    versionDocument.coordinate(), "OK", Map.of(
                            "mainClass", validation.className(),
                            "discoveredVia", validation.source(),
                            "sha256", stored.sha256(),
                            "signed", validation.signed(),
                            "allowedHosts", versionDocument.getPermissions().getAllowedHosts(),
                            "secretScopes", versionDocument.getPermissions().getSecretScopes()));
            eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(pluginId, version, "INSTALLED",
                    List.of()));
            log.info("Installed plugin {} from {} ({} bytes)", versionDocument.coordinate(), fileName,
                    content.length);

            if (request.activate()) {
                PluginHandle handle = load(pluginId, version);
                return versionRepository.findById(handle.versionMetadata().getId())
                        .orElse(versionDocument);
            }
            return versionDocument;
        } catch (IOException ex) {
            throw new PluginLoadException(String.valueOf(fileName), "could not stage the upload", ex);
        } finally {
            workspace.release(stagingDirectory);
        }
    }

    /**
     * Reads a plugin's own identity by instantiating it in a throwaway class loader.
     *
     * <p>Nothing from the probe survives: the instance, its classes and the loader are all discarded. Only
     * identity strings are kept. {@code initialize} is deliberately not called, so an archive can be
     * identified without being given any engine services.
     */
    private ProbeResult probe(Path stagedJar, String className, List<String> libraries) {
        Path libDirectory = stagedJar.getParent().resolve("probe-lib");
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(stagedJar.toUri().toURL());
            if (!libraries.isEmpty()) {
                for (Path extracted : JarUtils.extractBundledLibraries(stagedJar, libDirectory)) {
                    urls.add(extracted.toUri().toURL());
                }
            }
        } catch (IOException ex) {
            throw new PluginValidationException("Could not prepare the archive for identification: "
                    + ex.getMessage());
        }

        try (PluginClassLoader probeLoader = new PluginClassLoader("probe:" + className,
                urls.toArray(new URL[0]), getClass().getClassLoader(), List.of())) {
            Class<?> type = probeLoader.loadClass(className);
            WorkflowPlugin instance = (WorkflowPlugin) type.getDeclaredConstructor().newInstance();
            ProbeResult result = new ProbeResult(
                    requireText(instance.getId(), "getId()"),
                    instance.getName(),
                    requireText(instance.getVersion(), "getVersion()"),
                    instance.getDescription(),
                    instance.getPluginType() == null ? PluginType.NODE : instance.getPluginType(),
                    instance.getApiVersion());
            log.debug("Probed plugin archive: {} v{} ({})", result.id(), result.version(), result.type());
            return result;
        } catch (ReflectiveOperationException | ClassCastException | IOException | LinkageError ex) {
            throw new PluginValidationException("Could not identify the plugin: " + ex.getClass()
                    .getSimpleName() + ": " + ex.getMessage());
        } finally {
            workspace.release(libDirectory);
        }
    }

    // -------------------------------------------------------------------- load

    @Override
    public PluginHandle load(String pluginId, String version) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            Optional<PluginHandle> existing = registry.find(pluginId, version);
            if (existing.isPresent() && existing.get().state() == PluginState.ACTIVE) {
                return existing.get();
            }
            PluginVersion metadata = requireVersion(pluginId, version);
            if (metadata.getStatus() == PluginStatus.DELETED) {
                throw new PluginLoadException(metadata.coordinate(), "the version has been deleted");
            }
            return doLoad(metadata);
        } finally {
            lock.unlock();
        }
    }

    private PluginHandle doLoad(PluginVersion metadata) {
        String coordinate = metadata.coordinate();
        Path directory = workspace.allocate(metadata.getPluginId(), metadata.getVersion());
        Path jar = directory.resolve(metadata.getPluginId() + "-" + metadata.getVersion() + ".jar");
        PluginClassLoader classLoader = null;
        try {
            jarStorage.writeTo(metadata.getJarFileId(), metadata.getSha256(), jar);

            List<URL> urls = new ArrayList<>();
            urls.add(jar.toUri().toURL());
            for (Path bundled : JarUtils.extractBundledLibraries(jar, directory.resolve("lib"))) {
                urls.add(bundled.toUri().toURL());
            }
            classLoader = new PluginClassLoader(coordinate, urls.toArray(new URL[0]),
                    getClass().getClassLoader(), List.of());

            Class<?> type = classLoader.loadClass(metadata.getMainClass());
            WorkflowPlugin instance = (WorkflowPlugin) type.getDeclaredConstructor().newInstance();

            if (!instance.getId().equals(metadata.getPluginId())
                    || !instance.getVersion().equals(metadata.getVersion())) {
                throw new PluginLoadException(coordinate, "the archive now reports itself as "
                        + instance.getId() + ":" + instance.getVersion()
                        + ", which does not match the stored metadata");
            }

            List<NodeDefinition> nodeDefinitions = List.of();
            PluginDescriptor bootstrapDescriptor = PluginDescriptor.builder(instance.getId(),
                            instance.getVersion())
                    .name(instance.getName())
                    .description(instance.getDescription())
                    .type(instance.getPluginType())
                    .apiVersion(instance.getApiVersion())
                    .build();

            DefaultPluginContext context = contextFactory.create(bootstrapDescriptor, metadata, directory);
            instance.initialize(context);

            if (instance instanceof WorkflowNodePlugin) {
                nodeDefinitions = safeNodeDefinitions((WorkflowNodePlugin) instance, coordinate);
            }
            PluginDescriptor descriptor = PluginDescriptor.builder(instance.getId(), instance.getVersion())
                    .name(instance.getName())
                    .description(instance.getDescription())
                    .type(instance.getPluginType())
                    .apiVersion(instance.getApiVersion())
                    .nodeDefinitions(nodeDefinitions)
                    .build();

            // Complete the descriptor in place rather than building a second context: the plugin already
            // holds this one, and a second context would carry a second redactor, so secrets the plugin reads
            // would be tracked in an object the engine no longer consults.
            context.completeDescriptor(descriptor);
            PluginHandle handle = new PluginHandle(descriptor, instance, classLoader, context, metadata,
                    directory);
            handle.state(PluginState.ACTIVE);

            if (instance instanceof TriggerPlugin) {
                startTrigger((TriggerPlugin) instance, handle, context);
            }

            registry.register(handle);
            persistLoadSuccess(metadata, nodeDefinitions);
            eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(metadata.getPluginId(),
                    metadata.getVersion(), "LOADED", handle.nodeTypes()));
            log.info("Loaded plugin {} ({}) contributing node types {}", coordinate,
                    descriptor.type(), handle.nodeTypes());
            return handle;
        } catch (PluginLoadException ex) {
            cleanUpFailedLoad(classLoader, directory);
            persistLoadFailure(metadata, ex.getMessage());
            throw ex;
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError ex) {
            cleanUpFailedLoad(classLoader, directory);
            String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            persistLoadFailure(metadata, message);
            throw new PluginLoadException(coordinate, message, ex);
        }
    }

    /**
     * Calls the plugin for its node definitions, treating a failure as a load failure.
     *
     * <p>A node plugin that cannot describe its own node types is unusable, and finding that out now beats
     * finding out when a workflow reaches one of its nodes.
     */
    private List<NodeDefinition> safeNodeDefinitions(WorkflowNodePlugin plugin, String coordinate) {
        List<NodeDefinition> definitions;
        try {
            definitions = plugin.getNodeDefinitions();
        } catch (RuntimeException ex) {
            throw new PluginLoadException(coordinate, "getNodeDefinitions() failed: " + ex.getMessage(), ex);
        }
        if (definitions == null || definitions.isEmpty()) {
            throw new PluginLoadException(coordinate,
                    "a NODE plugin must contribute at least one node definition");
        }
        return List.copyOf(definitions);
    }

    private void startTrigger(TriggerPlugin trigger, PluginHandle handle, DefaultPluginContext context) {
        CoreTriggerContext triggerContext = new CoreTriggerContext(handle.coordinate(), context,
                eventPublisher);
        triggerContexts.put(handle.coordinate(), triggerContext);
        try {
            trigger.start(triggerContext);
            log.info("Started trigger plugin {}", handle.coordinate());
        } catch (RuntimeException ex) {
            triggerContexts.remove(handle.coordinate());
            throw new PluginLoadException(handle.coordinate(), "start() failed: " + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ unload

    @Override
    public boolean unload(String pluginId, String version, boolean force) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            Optional<PluginHandle> found = registry.find(pluginId, version);
            if (found.isEmpty()) {
                return false;
            }
            PluginHandle handle = found.get();
            long grace = properties.getPlugins().getUnloadGraceMillis();

            handle.beginDraining();
            boolean quiescent = handle.awaitQuiescence(grace);
            if (!quiescent && !force) {
                // Refuse rather than break a running node. The caller can retry with force when the
                // operational situation warrants it.
                handle.state(PluginState.ACTIVE);
                throw new PluginLoadException(handle.coordinate(), handle.activeLeaseCount()
                        + " invocation(s) still in flight after " + grace
                        + " ms. Retry, or use force=true to unload anyway.");
            }
            if (!quiescent) {
                log.warn("Forcing unload of {} with {} invocation(s) still in flight; those nodes will fail",
                        handle.coordinate(), handle.activeLeaseCount());
            }

            registry.unregister(pluginId, version);
            stopTrigger(handle);
            destroyQuietly(handle);
            closeQuietly(handle.classLoader(), handle.coordinate());
            handle.state(PluginState.UNLOADED);
            workspace.release(handle.workspace());

            versionRepository.findById(PluginVersion.idFor(pluginId, version)).ifPresent(metadata -> {
                metadata.setLastUnloadedAt(Instant.now());
                versionRepository.save(metadata);
            });
            eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(pluginId, version, "UNLOADED",
                    handle.nodeTypes()));
            log.info("Unloaded plugin {} after {} invocation(s), {} failed", handle.coordinate(),
                    handle.totalInvocations(), handle.failedInvocations());
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PluginHandle reload(String pluginId, String version) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            unload(pluginId, version, false);
            PluginHandle handle = load(pluginId, version);
            eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(pluginId, version, "RELOADED",
                    handle.nodeTypes()));
            return handle;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PluginVersion updatePermissions(String pluginId, String version, PermissionUpdate update,
                                           String actor) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            PluginVersion metadata = requireVersion(pluginId, version);
            PluginPermissions permissions = metadata.getPermissions();

            // Kept for the audit record: granting or removing a host is exactly the change somebody later
            // needs to see who made and when.
            List<String> previousHosts = new ArrayList<>(permissions.getAllowedHosts());

            permissions.setAllowedHosts(update.allowedHosts());
            permissions.setSecretScopes(update.secretScopes());
            permissions.setEventsEnabled(update.eventsEnabled());
            // maxHttpTimeoutMillis, maxResponseBytes and dataStoreEnabled are intentionally left as they were:
            // this method changes only what the console exposes, and must not reset what it does not carry.
            versionRepository.save(metadata);

            auditService.record(actor, "PLUGIN_PERMISSIONS_UPDATED", "PLUGIN", metadata.coordinate(), "OK",
                    Map.of("allowedHosts", permissions.getAllowedHosts(),
                            "previousAllowedHosts", previousHosts,
                            "secretScopes", permissions.getSecretScopes(),
                            "eventsEnabled", permissions.isEventsEnabled()));

            // Apply now, but only to a version that is actually loaded. Reloading rebuilds the PluginContext
            // from the metadata just saved, so the HTTP client picks up the new allowlist. An inactive version
            // is left unloaded: changing its permissions must not quietly start it.
            if (registry.find(pluginId, version).isPresent()) {
                unload(pluginId, version, false);
                PluginHandle handle = load(pluginId, version);
                eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(pluginId, version, "RELOADED",
                        handle.nodeTypes()));
                log.info("Plugin {}:{} permissions updated by {} and reloaded; hosts now {}", pluginId,
                        version, actor, permissions.getAllowedHosts());
            } else {
                log.info("Plugin {}:{} permissions updated by {}; not loaded, so no reload; hosts now {}",
                        pluginId, version, actor, permissions.getAllowedHosts());
            }
            return metadata;
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------- status changes

    @Override
    public PluginHandle activate(String pluginId, String version, String actor) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            PluginVersion metadata = requireVersion(pluginId, version);
            metadata.setStatus(PluginStatus.ACTIVE);
            metadata.setLoadError(null);
            versionRepository.save(metadata);
            PluginHandle handle = load(pluginId, version);
            updateHeadStatus(pluginId, PluginStatus.ACTIVE, version, actor);
            auditService.record(actor, "PLUGIN_ACTIVATED", "PLUGIN", metadata.coordinate(), "OK",
                    Map.of("nodeTypes", handle.nodeTypes()));
            eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(pluginId, version, "ACTIVATED",
                    handle.nodeTypes()));
            return handle;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deactivate(String pluginId, String version, String actor) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            PluginVersion metadata = requireVersion(pluginId, version);
            metadata.setStatus(PluginStatus.INACTIVE);
            versionRepository.save(metadata);
            unload(pluginId, version, true);
            auditService.record(actor, "PLUGIN_DEACTIVATED", "PLUGIN", metadata.coordinate(), "OK", null);
            eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(pluginId, version, "DEACTIVATED",
                    List.of()));
            log.info("Deactivated plugin {}", metadata.coordinate());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int delete(String pluginId, String version, String actor) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            List<PluginVersion> targets = version == null || version.isBlank()
                    ? versionRepository.findByPluginIdOrderByUploadedAtDesc(pluginId)
                    : List.of(requireVersion(pluginId, version));
            if (targets.isEmpty()) {
                throw new PluginNotFoundException(pluginId);
            }
            int deleted = 0;
            for (PluginVersion metadata : targets) {
                unload(metadata.getPluginId(), metadata.getVersion(), true);
                jarStorage.delete(metadata.getJarFileId());
                versionRepository.delete(metadata);
                auditService.record(actor, "PLUGIN_DELETED", "PLUGIN", metadata.coordinate(), "OK", null);
                eventPublisher.publishPluginEvent(PluginLifecycleEvent.of(metadata.getPluginId(),
                        metadata.getVersion(), "DELETED", List.of()));
                deleted++;
            }
            if (versionRepository.findByPluginIdOrderByUploadedAtDesc(pluginId).isEmpty()) {
                metadataRepository.deleteById(pluginId);
                log.info("Removed plugin head {} because no versions remain", pluginId);
            }
            return deleted;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setDefaultVersion(String pluginId, String version, String actor) {
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            registry.setDefaultVersion(pluginId, version);
            metadataRepository.findById(pluginId).ifPresent(head -> {
                head.setDefaultVersion(version);
                head.setUpdatedAt(Instant.now());
                head.setUpdatedBy(actor);
                metadataRepository.save(head);
            });
            auditService.record(actor, "PLUGIN_DEFAULT_VERSION_SET", "PLUGIN", pluginId + ":" + version,
                    "OK", null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int loadActiveVersions() {
        List<PluginVersion> active = versionRepository.findByStatus(PluginStatus.ACTIVE);
        if (active.isEmpty()) {
            log.info("No active plugin versions to load");
            return 0;
        }
        int loaded = 0;
        for (PluginVersion metadata : active) {
            try {
                load(metadata.getPluginId(), metadata.getVersion());
                loaded++;
            } catch (RuntimeException ex) {
                // One broken plugin must not stop the engine from starting. Its status is recorded as FAILED
                // and workflows that need it fail with a clear error.
                log.error("Could not load plugin {} at startup: {}", metadata.coordinate(), ex.getMessage());
            }
        }
        log.info("Loaded {} of {} active plugin version(s) at startup", loaded, active.size());
        return loaded;
    }

    @Override
    public Collection<PluginHandle> loaded() {
        return registry.handles();
    }

    @PreDestroy
    void shutdown() {
        for (PluginHandle handle : new ArrayList<>(registry.handles())) {
            try {
                unload(handle.pluginId(), handle.version(), true);
            } catch (RuntimeException ex) {
                log.warn("Could not unload {} during shutdown: {}", handle.coordinate(), ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Identity read from an archive during the probe.
     *
     * @param id          plugin id the archive reports
     * @param name        display name
     * @param version     version the archive reports
     * @param description description
     * @param type        extension category
     * @param apiVersion  SDK version it was built against
     */
    private record ProbeResult(String id, String name, String version, String description, PluginType type,
                               int apiVersion) {
    }

    private void verifyDeclared(PluginUploadRequest request, String pluginId, String version) {
        List<String> problems = new ArrayList<>();
        if (request.pluginId() != null && !request.pluginId().isBlank()
                && !request.pluginId().equals(pluginId)) {
            problems.add("the upload declares pluginId '" + request.pluginId() + "' but the archive reports '"
                    + pluginId + "'");
        }
        if (request.version() != null && !request.version().isBlank()
                && !request.version().equals(version)) {
            problems.add("the upload declares version '" + request.version() + "' but the archive reports '"
                    + version + "'");
        }
        if (!problems.isEmpty()) {
            throw new PluginValidationException(problems);
        }
    }

    private PluginVersion buildVersionDocument(PluginUploadRequest request,
                                               PluginJarValidator.ValidationResult validation,
                                               ProbeResult probe, String fileName,
                                               PluginJarStorage.StoredJar stored) {
        PluginVersion document = new PluginVersion();
        document.setId(PluginVersion.idFor(probe.id(), probe.version()));
        document.setPluginId(probe.id());
        document.setVersion(probe.version());
        document.setName(probe.name());
        document.setDescription(request.description() != null && !request.description().isBlank()
                ? request.description() : probe.description());
        document.setPluginType(probe.type().name());
        document.setMainClass(validation.className());
        document.setApiVersion(validation.apiVersion());
        document.setJarFileName(fileName == null ? probe.id() + ".jar" : fileName);
        document.setJarFileId(stored.fileId());
        document.setJarSizeBytes(stored.size());
        document.setSha256(stored.sha256());
        document.setSigned(validation.signed());
        document.setStatus(request.activate() ? PluginStatus.ACTIVE : PluginStatus.INSTALLED);
        document.setSettings(request.settings() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.settings()));
        document.setDependencies(request.dependencies() == null ? List.of()
                : new ArrayList<>(request.dependencies()));
        document.setUploadedAt(Instant.now());
        document.setUploadedBy(request.actor());

        PluginPermissions permissions = new PluginPermissions();
        permissions.setAllowedHosts(request.allowedHosts());
        permissions.setSecretScopes(request.secretScopes());
        permissions.setEventsEnabled(request.eventsEnabled());
        permissions.setDataStoreEnabled(true);
        document.setPermissions(permissions);
        return document;
    }

    /**
     * Stores the icon the archive ships, if any.
     *
     * <p>Never fails an install. An icon is decoration, and refusing a working plugin over a picture would be
     * a worse outcome than a plugin with no picture — the designer already falls back to a built-in glyph.
     *
     * @param document the version being written
     * @param stagedJar the archive, still on disk from validation
     */
    private void applyIcon(PluginVersion document, Path stagedJar) {
        try {
            iconExtractor.extract(stagedJar, declaredIconPath(stagedJar)).ifPresent(icon -> {
                document.setIconDataUrl(icon.toDataUrl());
                document.setIconSource(icon.fileName());
                log.info("Plugin {} ships an icon: {} ({} bytes)", document.coordinate(), icon.fileName(),
                        icon.data().length);
            });
        } catch (RuntimeException ex) {
            log.warn("Could not read an icon for {}: {}", document.coordinate(), ex.getMessage());
        }
    }

    /** @return the manifest's {@code icon} path when it names a file in the archive, else null */
    private String declaredIconPath(Path stagedJar) {
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(stagedJar.toFile())) {
            var entry = archive.getJarEntry(com.orchpilot.workflow.sdk.manifest.PluginManifest.LOCATION);
            if (entry == null) {
                return null;
            }
            String json;
            try (var stream = archive.getInputStream(entry)) {
                json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            Object icon = com.orchpilot.workflow.sdk.json.Json.parseObject(json).get("icon");
            // A top-level "icon" is only a file path when it looks like one. Existing manifests use it for a
            // glyph hint such as "cloud", and those must keep meaning what they always did.
            String value = icon == null ? null : String.valueOf(icon).trim();
            return value != null && (value.endsWith(".svg") || value.endsWith(".png")) ? value : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private void upsertPluginHead(PluginVersion version, String actor) {
        PluginMetadata head = metadataRepository.findById(version.getPluginId()).orElseGet(() -> {
            PluginMetadata fresh = new PluginMetadata();
            fresh.setId(version.getPluginId());
            fresh.setCreatedAt(Instant.now());
            fresh.setCreatedBy(actor);
            return fresh;
        });
        head.setName(version.getName());
        head.setDescription(version.getDescription());
        head.setPluginType(version.getPluginType());
        head.setLatestVersion(version.getVersion());
        head.setDefaultVersion(version.getVersion());
        head.setStatus(version.getStatus());
        head.setUpdatedAt(Instant.now());
        head.setUpdatedBy(actor);
        metadataRepository.save(head);
    }

    private void updateHeadStatus(String pluginId, PluginStatus status, String defaultVersion, String actor) {
        metadataRepository.findById(pluginId).ifPresent(head -> {
            head.setStatus(status);
            if (defaultVersion != null) {
                head.setDefaultVersion(defaultVersion);
            }
            head.setUpdatedAt(Instant.now());
            head.setUpdatedBy(actor);
            metadataRepository.save(head);
        });
    }

    private void persistLoadSuccess(PluginVersion metadata, List<NodeDefinition> definitions) {
        metadata.setStatus(PluginStatus.ACTIVE);
        metadata.setLastLoadedAt(Instant.now());
        metadata.setLoadError(null);
        metadata.setNodeTypes(definitions.stream().map(NodeDefinition::nodeType).toList());
        metadata.setNodeDefinitions(definitions.stream().map(DefaultPluginManager::toRecord).toList());
        versionRepository.save(metadata);
    }

    private void persistLoadFailure(PluginVersion metadata, String message) {
        try {
            metadata.setStatus(PluginStatus.FAILED);
            metadata.setLoadError(message == null ? "unknown error" : message);
            versionRepository.save(metadata);
        } catch (RuntimeException ex) {
            log.warn("Could not record the load failure of {}: {}", metadata.coordinate(), ex.getMessage());
        }
    }

    private static NodeDefinitionRecord toRecord(NodeDefinition definition) {
        NodeDefinitionRecord record = new NodeDefinitionRecord();
        record.setNodeType(definition.nodeType());
        record.setDisplayName(definition.displayName());
        record.setCategory(definition.category());
        record.setIcon(definition.icon());
        record.setDescription(definition.description());
        record.setConfigurationSchema(new LinkedHashMap<>(definition.configurationSchema()));
        record.setOutputPorts(new ArrayList<>(definition.outputPorts()));
        record.setOutputVariables(new ArrayList<>(definition.outputVariables()));
        record.setIdempotent(definition.idempotent());
        record.setSupportsRetry(definition.supportsRetry());
        return record;
    }

    private void stopTrigger(PluginHandle handle) {
        CoreTriggerContext triggerContext = triggerContexts.remove(handle.coordinate());
        if (triggerContext != null) {
            triggerContext.markStopping();
        }
        if (handle.instance() instanceof TriggerPlugin) {
            try {
                ((TriggerPlugin) handle.instance()).stop();
            } catch (RuntimeException ex) {
                log.warn("stop() of trigger plugin {} threw {}", handle.coordinate(), ex.getMessage());
            }
        }
    }

    /**
     * Calls {@code destroy()} and swallows anything it throws.
     *
     * <p>A plugin must not be able to prevent its own removal. If {@code destroy} fails, the class loader is
     * still closed and the registration is still gone; the plugin may leak its own resources, which is its
     * problem, not the engine's.
     */
    private void destroyQuietly(PluginHandle handle) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(handle.classLoader());
        try {
            handle.instance().destroy();
        } catch (RuntimeException | LinkageError ex) {
            log.warn("destroy() of plugin {} threw {}: {}", handle.coordinate(),
                    ex.getClass().getSimpleName(), ex.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void closeQuietly(PluginClassLoader classLoader, String coordinate) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException ex) {
            log.warn("Could not close the class loader of {}: {}", coordinate, ex.getMessage());
        }
    }

    private void cleanUpFailedLoad(PluginClassLoader classLoader, Path directory) {
        closeQuietly(classLoader, "failed-load");
        workspace.release(directory);
    }

    private PluginVersion requireVersion(String pluginId, String version) {
        return versionRepository.findByPluginIdAndVersion(pluginId, version)
                .orElseThrow(() -> new PluginNotFoundException(pluginId, version));
    }

    private ReentrantLock lockFor(String pluginId) {
        return locks.computeIfAbsent(pluginId == null ? "" : pluginId, key -> new ReentrantLock());
    }

    private static String requireText(String value, String accessor) {
        if (value == null || value.isBlank()) {
            throw new PluginValidationException("The plugin returned a blank value from " + accessor);
        }
        return value.trim();
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload.jar";
        }
        String base = fileName.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "upload.jar" : cleaned;
    }
}
