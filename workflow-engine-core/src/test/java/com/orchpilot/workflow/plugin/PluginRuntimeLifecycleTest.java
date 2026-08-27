package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.PluginExecutionRecord;
import com.orchpilot.workflow.model.PluginMetadata;
import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.plugin.context.PluginContextFactory;
import com.orchpilot.workflow.repository.PluginExecutionRepository;
import com.orchpilot.workflow.repository.PluginMetadataRepository;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.SecretService;
import com.orchpilot.workflow.support.TestContexts;
import com.orchpilot.workflow.support.TestJars;
import com.orchpilot.workflow.support.testplugin.EchoPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole plugin lifecycle against the real manager, validator, class loader, registry and node executor.
 *
 * <p>Only the database and GridFS are substituted, and only because they are I/O. Everything that makes the plugin
 * platform work, archive validation, identity probing, isolated class loading, initialisation, node type registration,
 * dispatch through the node executor, draining and unloading, is the production code path. That is deliberate: a test
 * that mocked the manager would tell us nothing about whether a JAR can actually be loaded and executed.
 *
 * <p>This is the unit-level counterpart to {@code PluginLifecycleIT}, which does the same thing over HTTP against a real
 * MongoDB.
 */
class PluginRuntimeLifecycleTest {

    private static final String ACTOR = "test-operator";

    private final Map<String, PluginVersion> storedVersions = new HashMap<>();
    private final Map<String, byte[]> storedJars = new HashMap<>();

    private DefaultPluginManager manager;
    private DefaultPluginRegistry registry;
    private PluginNodeExecutor nodeExecutor;
    private PluginWorkspace workspace;
    private WorkflowEngineProperties properties;

    @BeforeEach
    void setUp(@TempDir Path workspaceRoot) {
        properties = new WorkflowEngineProperties();
        properties.getPlugins().setWorkspaceDirectory(workspaceRoot.toString());
        properties.getPlugins().setDefaultAllowedHosts(List.of());

        PluginMetadataRepository metadataRepository = mock(PluginMetadataRepository.class);
        when(metadataRepository.findById(anyString())).thenAnswer(invocation -> Optional.empty());
        when(metadataRepository.save(any(PluginMetadata.class))).thenAnswer(inv -> inv.getArgument(0));

        PluginVersionRepository versionRepository = mock(PluginVersionRepository.class);
        when(versionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> {
            PluginVersion saved = invocation.getArgument(0);
            storedVersions.put(saved.getId(), saved);
            return saved;
        });
        when(versionRepository.findByPluginIdAndVersion(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(storedVersions.get(
                        PluginVersion.idFor(invocation.getArgument(0), invocation.getArgument(1)))));
        when(versionRepository.findById(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(storedVersions.get(invocation.<String>getArgument(0))));
        when(versionRepository.existsByPluginIdAndVersion(anyString(), anyString())).thenAnswer(invocation ->
                storedVersions.containsKey(
                        PluginVersion.idFor(invocation.getArgument(0), invocation.getArgument(1))));
        when(versionRepository.findByPluginIdOrderByUploadedAtDesc(anyString())).thenAnswer(invocation -> {
            List<PluginVersion> matches = new ArrayList<>();
            storedVersions.values().forEach(version -> {
                if (version.getPluginId().equals(invocation.<String>getArgument(0))) {
                    matches.add(version);
                }
            });
            return matches;
        });

        PluginExecutionRepository executionRepository = mock(PluginExecutionRepository.class);
        when(executionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(executionRepository.save(any(PluginExecutionRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SecretService secretService = mock(SecretService.class);
        AuditService auditService = mock(AuditService.class);
        WorkflowEventPublisher eventPublisher = mock(WorkflowEventPublisher.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);

        workspace = new PluginWorkspace(properties);
        registry = new DefaultPluginRegistry();
        PluginDiscoveryService discoveryService = new PluginDiscoveryService();
        PluginJarValidator validator = new PluginJarValidator(properties, discoveryService, versionRepository);
        PluginContextFactory contextFactory = new PluginContextFactory(secretService, auditService,
                mongoTemplate, eventPublisher, properties);

        manager = new DefaultPluginManager(metadataRepository, versionRepository, new InMemoryJarStorage(),
                validator, discoveryService, workspace, registry, contextFactory, eventPublisher,
                auditService, properties, new com.orchpilot.workflow.plugin.icon.PluginIconExtractor());
        // These lifecycle tests exercise loading, leasing and invocation, none of which touch workflow files;
        // a mock keeps the file accessor available without dragging storage configuration into the fixture.
        nodeExecutor = new PluginNodeExecutor(registry, executionRepository, properties,
                mock(com.orchpilot.workflow.storage.service.WorkflowFileStorageService.class));
    }

    @AfterEach
    void tearDown() {
        for (PluginHandle handle : new ArrayList<>(registry.handles())) {
            manager.unload(handle.pluginId(), handle.version(), true);
        }
    }

    /** GridFS stand-in: keeps bytes in a map and stages them to the workspace on demand. */
    private final class InMemoryJarStorage implements PluginJarStorage {

        @Override
        public StoredJar store(String pluginId, String version, String fileName, byte[] content) {
            String fileId = pluginId + ":" + version;
            storedJars.put(fileId, content);
            return new StoredJar(fileId, content.length,
                    com.orchpilot.workflow.utility.HashUtils.sha256Hex(content));
        }

        @Override
        public long writeTo(String fileId, String expectedSha256, Path target) {
            byte[] content = storedJars.get(fileId);
            assertNotNull(content, "the manager asked for a JAR that was never stored");
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, content);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            return content.length;
        }

        @Override
        public boolean delete(String fileId) {
            return storedJars.remove(fileId) != null;
        }

        @Override
        public Optional<Long> size(String fileId) {
            byte[] content = storedJars.get(fileId);
            return content == null ? Optional.empty() : Optional.of((long) content.length);
        }
    }

    private PluginUploadRequest uploadRequest(boolean activate) {
        return new PluginUploadRequest(null, null, null, "installed by a test", List.of(), List.of(),
                Map.of("greeting", "hello"), List.of(), null, activate, true, ACTOR);
    }

    private WorkflowNode echoNode(String message) {
        WorkflowNode node = TestContexts.node("echo-1", NodeTypes.PLUGIN);
        node.setPluginId("echo");
        node.setPluginVersion("1.0.0");
        node.setConfiguration(Map.of("message", message));
        return node;
    }

    private WorkflowExecutionContext contextFor(WorkflowNode node, Map<String, Object> variables) {
        return TestContexts.context(TestContexts.version(List.of(node), List.of()), variables,
                new TestContexts.RecordingLogWriter());
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("installing a JAR loads it, registers its node types and makes it executable")
    void installLoadsRegistersAndExecutes() {
        PluginVersion installed = manager.install("echo-plugin-1.0.0.jar",
                TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));

        assertEquals("echo", installed.getPluginId());
        assertEquals("1.0.0", installed.getVersion());
        assertEquals(EchoPlugin.class.getName(), installed.getMainClass());
        assertEquals(PluginStatus.ACTIVE, installed.getStatus());
        assertEquals(List.of(EchoPlugin.NODE_TYPE), installed.getNodeTypes());
        assertFalse(installed.getSha256().isBlank());

        PluginHandle handle = registry.find("echo", "1.0.0").orElseThrow();
        assertEquals(PluginState.ACTIVE, handle.state());
        assertEquals(List.of(EchoPlugin.NODE_TYPE), handle.nodeTypes());
        assertTrue(registry.findByNodeType(EchoPlugin.NODE_TYPE).isPresent(),
                "the node type must be resolvable without a restart");

        WorkflowNode node = echoNode("${greeting} world");
        NodeExecutionResult result = nodeExecutor.execute(node,
                contextFor(node, Map.of("greeting", "hello")));

        assertTrue(result.isSuccess(), String.valueOf(result));
        assertEquals("hello world", result.outputs().get("message"),
                "the plugin must receive configuration with variables already resolved");
        assertEquals(1, result.outputs().get("attempt"));
        assertNotNull(result.outputs().get("idempotencyKey"));
        assertEquals(1, handle.totalInvocations());
    }

    @Test
    @DisplayName("the plugin's declared node definition is persisted, so the catalogue survives a restart")
    void nodeDefinitionsArePersisted() {
        PluginVersion installed = manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class),
                uploadRequest(true));

        assertEquals(1, installed.getNodeDefinitions().size());
        var definition = installed.getNodeDefinitions().get(0);
        assertEquals(EchoPlugin.NODE_TYPE, definition.getNodeType());
        assertEquals("Testing", definition.getCategory());
        assertTrue(definition.getConfigurationSchema().containsKey("properties"));
    }

    @Test
    @DisplayName("a plugin declared through ServiceLoader is discovered too")
    void serviceLoaderDiscoveryWorks() {
        PluginVersion installed = manager.install("echo-sl.jar",
                TestJars.serviceLoaderPluginJar(EchoPlugin.class), uploadRequest(true));

        assertEquals(EchoPlugin.class.getName(), installed.getMainClass());
        assertTrue(registry.findByNodeType(EchoPlugin.NODE_TYPE).isPresent());
    }

    @Test
    @DisplayName("installing without activating stores the plugin but does not load it")
    void installWithoutActivateDoesNotLoad() {
        PluginVersion installed = manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class),
                uploadRequest(false));

        assertEquals(PluginStatus.INSTALLED, installed.getStatus());
        assertTrue(registry.find("echo", "1.0.0").isEmpty());

        manager.activate("echo", "1.0.0", ACTOR);

        assertTrue(registry.find("echo", "1.0.0").isPresent());
    }

    @Test
    @DisplayName("unloading removes the node type, and a workflow using it then fails with a clear error")
    void unloadingMakesTheNodeTypeUnavailable() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));

        assertTrue(manager.unload("echo", "1.0.0", false));

        assertTrue(registry.findByNodeType(EchoPlugin.NODE_TYPE).isEmpty());
        WorkflowNode node = echoNode("hello");
        NodeExecutionResult result = nodeExecutor.execute(node, contextFor(node, Map.of()));

        assertTrue(result.isFailed());
        assertEquals("PLUGIN_NOT_AVAILABLE", result.errorCode());
        assertTrue(result.errorMessage().contains("echo"), result.errorMessage());
    }

    @Test
    @DisplayName("destroy is called on unload so a plugin can release its own resources")
    void destroyIsCalledOnUnload() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle handle = registry.find("echo", "1.0.0").orElseThrow();

        // The instance was loaded in the plugin's own class loader, so it is a different class from the test's
        // EchoPlugin. Reflection is the honest way to observe it, and confirms the isolation is real.
        Object instance = handle.instance();
        assertFalse(instance.getClass().equals(EchoPlugin.class),
                "the loaded class must come from the JAR, not from the test classpath");

        manager.unload("echo", "1.0.0", true);

        assertEquals(PluginState.UNLOADED, handle.state());
        assertTrue(invokeBoolean(instance, "isDestroyed"), "destroy() must run so the plugin can clean up");
    }

    @Test
    @DisplayName("deactivating is the kill switch: the version is marked inactive and unloaded")
    void deactivateMarksInactiveAndUnloads() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));

        manager.deactivate("echo", "1.0.0", ACTOR);

        assertTrue(registry.find("echo", "1.0.0").isEmpty());
        assertEquals(PluginStatus.INACTIVE,
                storedVersions.get(PluginVersion.idFor("echo", "1.0.0")).getStatus());
    }

    @Test
    @DisplayName("reloading produces a fresh class loader while keeping the same coordinate")
    void reloadReplacesTheClassLoader() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle before = registry.find("echo", "1.0.0").orElseThrow();

        PluginHandle after = manager.reload("echo", "1.0.0");

        assertEquals(before.coordinate(), after.coordinate());
        assertFalse(before == after, "reload must produce a new handle");
        assertFalse(before.classLoader() == after.classLoader(), "reload must produce a new class loader");
        assertEquals(PluginState.ACTIVE, after.state());
    }

    @Test
    @DisplayName("updating permissions persists the new hosts and reloads a loaded version")
    void updatePermissionsPersistsAndReloads() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle before = registry.find("echo", "1.0.0").orElseThrow();

        PluginVersion updated = manager.updatePermissions("echo", "1.0.0",
                new PluginManager.PermissionUpdate(List.of("api.example.com", "*.sendgrid.com"),
                        List.of("echo."), false),
                ACTOR);

        // Persisted: the stored version carries the new lists.
        assertEquals(List.of("api.example.com", "*.sendgrid.com"),
                updated.getPermissions().getAllowedHosts());
        assertEquals(List.of("echo."), updated.getPermissions().getSecretScopes());
        assertFalse(updated.getPermissions().isEventsEnabled());
        assertEquals(List.of("api.example.com", "*.sendgrid.com"),
                storedVersions.get("echo:1.0.0").getPermissions().getAllowedHosts());

        // Applied: a loaded version is reloaded so the new allowlist is live, which is a fresh class loader.
        PluginHandle after = registry.find("echo", "1.0.0").orElseThrow();
        assertFalse(before == after, "a loaded version should be reloaded so the change takes effect");
        assertEquals(PluginState.ACTIVE, after.state());
    }

    @Test
    @DisplayName("updating permissions leaves an unrelated ceiling untouched")
    void updatePermissionsPreservesOtherFields() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        // Set a ceiling the editor never touches, on the stored version directly.
        storedVersions.get("echo:1.0.0").getPermissions().setMaxResponseBytes(4096L);

        manager.updatePermissions("echo", "1.0.0",
                new PluginManager.PermissionUpdate(List.of("api.example.com"), List.of(), true), ACTOR);

        // The editor carries only hosts, scopes and events; it must not reset a ceiling it does not send.
        assertEquals(4096L, storedVersions.get("echo:1.0.0").getPermissions().getMaxResponseBytes());
    }

    @Test
    @DisplayName("updating permissions on an inactive version does not load it")
    void updatePermissionsDoesNotLoadInactive() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(false));
        assertTrue(registry.find("echo", "1.0.0").isEmpty(), "precondition: not loaded");

        manager.updatePermissions("echo", "1.0.0",
                new PluginManager.PermissionUpdate(List.of("api.example.com"), List.of(), true), ACTOR);

        // Changing an inactive version's permissions must not quietly start it.
        assertTrue(registry.find("echo", "1.0.0").isEmpty(),
                "an inactive version must stay unloaded after a permission change");
        assertEquals(List.of("api.example.com"),
                storedVersions.get("echo:1.0.0").getPermissions().getAllowedHosts());
    }

    @Test
    @DisplayName("a draining plugin refuses new work with a retryable failure")
    void drainingPluginRefusesWorkRetryably() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle handle = registry.find("echo", "1.0.0").orElseThrow();
        handle.beginDraining();

        WorkflowNode node = echoNode("hello");
        NodeExecutionResult result = nodeExecutor.execute(node, contextFor(node, Map.of()));

        assertTrue(result.isFailed());
        assertEquals("PLUGIN_UNAVAILABLE", result.errorCode());
        assertTrue(result.retryable(), "a reload in progress should be retried, not treated as permanent");
    }

    @Test
    @DisplayName("a failure returned by the plugin is passed through and counted")
    void pluginFailureIsPassedThrough() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle handle = registry.find("echo", "1.0.0").orElseThrow();

        WorkflowNode node = echoNode("fail");
        NodeExecutionResult result = nodeExecutor.execute(node, contextFor(node, Map.of()));

        assertTrue(result.isFailed());
        assertEquals("ECHO_ASKED_TO_FAIL", result.errorCode());
        assertEquals(1, handle.failedInvocations());
        assertEquals(0, handle.activeLeaseCount(), "the lease must be released even when the node fails");
    }

    @Test
    @DisplayName("an exception thrown by a plugin becomes a failure result and does not escape")
    void pluginExceptionIsContained() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle handle = registry.find("echo", "1.0.0").orElseThrow();

        WorkflowNode node = echoNode("throw");
        NodeExecutionResult result = nodeExecutor.execute(node, contextFor(node, Map.of()));

        assertTrue(result.isFailed());
        assertEquals("PLUGIN_EXECUTION_ERROR", result.errorCode());
        assertEquals(0, handle.activeLeaseCount());
    }

    @Test
    @DisplayName("a node pinned to a version that is not loaded fails rather than silently using another")
    void pinnedVersionIsHonoured() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));

        WorkflowNode node = echoNode("hello");
        node.setPluginVersion("9.9.9");
        NodeExecutionResult result = nodeExecutor.execute(node, contextFor(node, Map.of()));

        assertTrue(result.isFailed());
        assertEquals("PLUGIN_NOT_AVAILABLE", result.errorCode());
        assertTrue(result.errorMessage().contains("9.9.9"), result.errorMessage());
    }

    @Test
    @DisplayName("installing the same version twice is rejected")
    void duplicateVersionIsRejected() {
        byte[] jar = TestJars.pluginJar(EchoPlugin.class);
        manager.install("echo.jar", jar, uploadRequest(true));

        PluginValidationException ex = assertThrows(PluginValidationException.class,
                () -> manager.install("echo.jar", jar, uploadRequest(true)));
        assertTrue(ex.getMessage().contains("already installed"), ex.getMessage());
    }

    @Test
    @DisplayName("an upload declaring the wrong id or version is rejected")
    void declaredIdentityMustMatchTheArchive() {
        byte[] jar = TestJars.pluginJar(EchoPlugin.class);
        PluginUploadRequest wrongId = new PluginUploadRequest("not-echo", null, null, null, List.of(),
                List.of(), Map.of(), List.of(), null, true, true, ACTOR);

        PluginValidationException ex = assertThrows(PluginValidationException.class,
                () -> manager.install("echo.jar", jar, wrongId));
        assertTrue(ex.getMessage().contains("not-echo"), ex.getMessage());
    }

    @Test
    @DisplayName("a file that is not a JAR is rejected before anything is loaded")
    void nonJarUploadIsRejected() {
        assertThrows(PluginValidationException.class,
                () -> manager.install("notes.txt", "this is not a jar".getBytes(),
                        uploadRequest(true)));
    }

    @Test
    @DisplayName("a JAR with no plugin class is rejected with an actionable message")
    void jarWithoutPluginClassIsRejected() {
        byte[] jar = TestJars.resourceOnlyJar("data/readme.txt", "no classes here");

        PluginValidationException ex = assertThrows(PluginValidationException.class,
                () -> manager.install("empty.jar", jar, uploadRequest(true)));
        assertTrue(ex.getMessage().contains("Workflow-Plugin-Class"), ex.getMessage());
    }

    @Test
    @DisplayName("a checksum that does not match the received bytes is rejected")
    void checksumMismatchIsRejected() {
        PluginUploadRequest withBadChecksum = new PluginUploadRequest(null, null, null, null, List.of(),
                List.of(), Map.of(), List.of(), "0".repeat(64), true, true, ACTOR);

        PluginValidationException ex = assertThrows(PluginValidationException.class,
                () -> manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), withBadChecksum));
        assertTrue(ex.getMessage().contains("Checksum mismatch"), ex.getMessage());
    }

    @Test
    @DisplayName("plugin settings from the upload reach the plugin as non-secret configuration")
    void settingsReachThePlugin() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));
        PluginHandle handle = registry.find("echo", "1.0.0").orElseThrow();

        assertEquals("hello", handle.pluginContext().settings().getString("greeting", "missing"));
    }

    @Test
    @DisplayName("deleting a version unloads it and removes its stored JAR")
    void deleteRemovesEverything() {
        manager.install("echo.jar", TestJars.pluginJar(EchoPlugin.class), uploadRequest(true));

        int deleted = manager.delete("echo", "1.0.0", ACTOR);

        assertEquals(1, deleted);
        assertTrue(registry.find("echo", "1.0.0").isEmpty());
        assertTrue(storedJars.isEmpty(), "the JAR must not be left behind in storage");
    }

    private static boolean invokeBoolean(Object target, String method) {
        try {
            return (Boolean) target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Could not call " + method + " on the loaded plugin", ex);
        }
    }
}
