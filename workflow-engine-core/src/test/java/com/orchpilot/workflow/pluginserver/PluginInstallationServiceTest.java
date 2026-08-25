package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.exception.InvalidWorkflowStateException;
import com.orchpilot.workflow.exception.PluginLoadException;
import com.orchpilot.workflow.exception.PluginNotFoundException;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.plugin.PluginManager;
import com.orchpilot.workflow.plugin.PluginUploadRequest;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Installing plugins that arrive from the registry.
 *
 * <p>The interesting behaviour is all in the refusals and the order of operations. An install must not begin before
 * the version is known to be runnable here, an update must not touch the running version until the new one is
 * loaded, and neither an uninstall nor a deactivation may proceed while a published workflow depends on the version.
 * Those are the tests; the happy path is the easy part.
 */
class PluginInstallationServiceTest {

    private static final byte[] ARCHIVE = "jar bytes".getBytes(StandardCharsets.UTF_8);

    private PluginCatalogSyncService catalog;
    private InstalledPluginRepository installed;
    private PluginInstallationRepository history;
    private PluginVersionRepository versions;
    private PluginArchiveDownloader downloader;
    private PluginUsageService usage;
    private PluginManager pluginManager;
    private AuditService audit;
    private PluginInstallationService service;

    @BeforeEach
    void setUp() {
        catalog = mock(PluginCatalogSyncService.class);
        installed = mock(InstalledPluginRepository.class);
        history = mock(PluginInstallationRepository.class);
        versions = mock(PluginVersionRepository.class);
        downloader = mock(PluginArchiveDownloader.class);
        usage = mock(PluginUsageService.class);
        pluginManager = mock(PluginManager.class);
        audit = mock(AuditService.class);

        service = new PluginInstallationService(catalog, installed, history, versions,
                new PluginCompatibilityService(), downloader, usage, pluginManager, audit);

        when(installed.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(installed.findById(anyString())).thenReturn(Optional.empty());
        when(versions.existsByPluginIdAndVersion(anyString(), anyString())).thenReturn(false);
        when(usage.dependents(anyString(), anyString(), any(), anyBoolean())).thenReturn(List.of());
        when(usage.isPinnedByPublishedWorkflow(anyString(), anyString(), any())).thenReturn(false);
    }

    // ------------------------------------------------------------------------------------------- fixtures

    private static CatalogRecords.CatalogEntry offering(String id, String latest, String status, String sdk,
                                                       String java, List<String> allVersions) {
        return new CatalogRecords.CatalogEntry(id, id + " plugin", "does things", "OrchPilot", latest, status,
                sdk, java, ">=1.0.0 <2.0.0", "checksum-" + latest, List.of(),
                allVersions.stream()
                        .map(version -> new CatalogRecords.CatalogVersion(version, "ACTIVE", sdk,
                                "checksum-" + version, ARCHIVE.length, List.of(id.toUpperCase() + "_NODE")))
                        .toList());
    }

    private void catalogHas(CatalogRecords.CatalogEntry entry) {
        when(catalog.entry(entry.pluginId())).thenReturn(Optional.of(entry));
    }

    private void downloadYields(String pluginId, String version) {
        when(downloader.fetch(eq(pluginId), eq(version), anyString())).thenReturn(
                new PluginArchiveDownloader.VerifiedArchive(pluginId, version, ARCHIVE,
                        "checksum-" + version, pluginId + "/" + version + "/" + pluginId + ".jar",
                        pluginId + "-" + version + ".jar"));
    }

    private void installYields(String pluginId, String version, String... nodeTypes) {
        PluginVersion document = new PluginVersion();
        document.setId(PluginVersion.idFor(pluginId, version));
        document.setPluginId(pluginId);
        document.setVersion(version);
        document.setNodeTypes(List.of(nodeTypes));
        document.setSha256("checksum-" + version);
        document.setJarSizeBytes(ARCHIVE.length);
        when(pluginManager.install(anyString(), any(), any())).thenReturn(document);
    }

    private InstalledPlugin locally(String pluginId, String defaultVersion, String... installedVersions) {
        InstalledPlugin plugin = new InstalledPlugin();
        plugin.setPluginId(pluginId);
        plugin.setName(pluginId + " plugin");
        for (String version : installedVersions) {
            plugin.put(new InstalledPlugin.InstalledVersion(version, InstallState.ACTIVE,
                    "checksum-" + version, null, ARCHIVE.length, "1.0.0",
                    List.of(pluginId.toUpperCase() + "_NODE"), Map.of(), Map.of(), Instant.now(), "vivek",
                    Instant.now(), null));
        }
        plugin.setDefaultVersion(defaultVersion);
        when(installed.findById(pluginId)).thenReturn(Optional.of(plugin));
        return plugin;
    }

    // -------------------------------------------------------------------------------------------- install

    @Nested
    @DisplayName("Install")
    class Install {

        @Test
        @DisplayName("downloads, installs, activates and records the result")
        void installsLatest() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));
            downloadYields("slack", "1.0.0");
            installYields("slack", "1.0.0", "SLACK_MESSAGE");

            PluginInstallationService.InstallationResult result = service.install("slack", null);

            assertEquals(PluginInstallationService.InstallationResult.Outcome.INSTALLED, result.outcome());
            assertEquals(InstallState.ACTIVE, result.state());
            assertEquals(List.of("SLACK_MESSAGE"), result.nodeTypes());
            verify(pluginManager).setDefaultVersion("slack", "1.0.0", "system");
        }

        @Test
        @DisplayName("passes the expected coordinate and checksum to the loader, and grants nothing")
        void installsWithNoPermissions() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));
            downloadYields("slack", "1.0.0");
            installYields("slack", "1.0.0", "SLACK_MESSAGE");

            service.install("slack", "1.0.0");

            ArgumentCaptor<PluginUploadRequest> request = ArgumentCaptor.forClass(PluginUploadRequest.class);
            verify(pluginManager).install(anyString(), any(), request.capture());
            assertEquals("slack", request.getValue().pluginId());
            assertEquals("1.0.0", request.getValue().version());
            assertEquals("checksum-1.0.0", request.getValue().expectedSha256());
            assertTrue(request.getValue().allowedHosts().isEmpty());
            assertTrue(request.getValue().secretScopes().isEmpty());
        }

        @Test
        @DisplayName("says so on the response when the plugin was granted nothing")
        void warnsAboutPermissions() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));
            downloadYields("slack", "1.0.0");
            installYields("slack", "1.0.0", "SLACK_MESSAGE");

            PluginInstallationService.InstallationResult result = service.install("slack", null);

            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("no allowed hosts")));
        }

        @Test
        @DisplayName("refuses a revoked plugin without downloading anything")
        void refusesRevoked() {
            catalogHas(offering("slack", "1.0.0", "REVOKED", "1.0.0", "17", List.of("1.0.0")));

            assertThrows(PluginValidationException.class, () -> service.install("slack", null));

            verify(downloader, never()).fetch(anyString(), anyString(), anyString());
            verify(pluginManager, never()).install(anyString(), any(), any());
        }

        @Test
        @DisplayName("refuses a version this engine cannot run, before downloading it")
        void refusesIncompatible() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "2.0.0", "17", List.of("1.0.0")));

            PluginValidationException failure = assertThrows(PluginValidationException.class,
                    () -> service.install("slack", null));

            assertTrue(failure.getMessage().contains("SDK 2.x"));
            verify(downloader, never()).fetch(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("refuses a plugin needing a newer Java than this engine runs")
        void refusesNewerJava() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0",
                    String.valueOf(Runtime.version().feature() + 1), List.of("1.0.0")));

            assertThrows(PluginValidationException.class, () -> service.install("slack", null));
            verify(downloader, never()).fetch(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a version the registry does not publish is a 404, not a download attempt")
        void refusesUnknownVersion() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));

            assertThrows(PluginNotFoundException.class, () -> service.install("slack", "9.9.9"));
            verify(downloader, never()).fetch(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a plugin the catalogue does not know is a 404")
        void refusesUnknownPlugin() {
            when(catalog.entry("nope")).thenReturn(Optional.empty());

            assertThrows(PluginNotFoundException.class, () -> service.install("nope", null));
        }

        @Test
        @DisplayName("a failed checksum leaves the version marked failed and clears the cache")
        void recordsChecksumFailure() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));
            when(downloader.fetch(eq("slack"), eq("1.0.0"), anyString()))
                    .thenThrow(new PluginValidationException("checksum does not match"));

            assertThrows(PluginValidationException.class, () -> service.install("slack", null));

            verify(downloader).release("slack", "1.0.0");
            verify(pluginManager, never()).install(anyString(), any(), any());

            ArgumentCaptor<InstalledPlugin> saved = ArgumentCaptor.forClass(InstalledPlugin.class);
            verify(installed, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            InstalledPlugin last = saved.getAllValues().get(saved.getAllValues().size() - 1);
            assertEquals(InstallState.INSTALL_FAILED, last.version("1.0.0").orElseThrow().state());
        }

        @Test
        @DisplayName("a version already present is adopted rather than downloaded again")
        void alreadyInstalled() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));
            locally("slack", "1.0.0", "1.0.0");

            PluginInstallationService.InstallationResult result = service.install("slack", "1.0.0");

            assertEquals(PluginInstallationService.InstallationResult.Outcome.ALREADY_INSTALLED,
                    result.outcome());
            verify(downloader, never()).fetch(anyString(), anyString(), anyString());
        }
    }

    // --------------------------------------------------------------------------------------------- update

    @Nested
    @DisplayName("Update")
    class Update {

        @Test
        @DisplayName("installs the new version, moves the default, then drains the old one")
        void updates() {
            catalogHas(offering("slack", "1.2.0", "ACTIVE", "1.0.0", "17", List.of("1.2.0", "1.0.0")));
            locally("slack", "1.0.0", "1.0.0");
            downloadYields("slack", "1.2.0");
            installYields("slack", "1.2.0", "SLACK_MESSAGE");

            PluginInstallationService.InstallationResult result = service.update("slack");

            assertEquals(PluginInstallationService.InstallationResult.Outcome.UPDATED, result.outcome());
            assertEquals("1.0.0", result.previousVersion());
            assertFalse(result.previousVersionRetained());
            verify(pluginManager).setDefaultVersion("slack", "1.2.0", "system");
            verify(pluginManager).unload("slack", "1.0.0", false);
            verify(pluginManager).deactivate("slack", "1.0.0", "system");
        }

        @Test
        @DisplayName("keeps the old version loaded when a published workflow pins it")
        void retainsPinnedOldVersion() {
            catalogHas(offering("slack", "1.2.0", "ACTIVE", "1.0.0", "17", List.of("1.2.0", "1.0.0")));
            locally("slack", "1.0.0", "1.0.0");
            downloadYields("slack", "1.2.0");
            installYields("slack", "1.2.0", "SLACK_MESSAGE");
            when(usage.isPinnedByPublishedWorkflow(eq("slack"), eq("1.0.0"), any())).thenReturn(true);

            PluginInstallationService.InstallationResult result = service.update("slack");

            assertTrue(result.previousVersionRetained());
            verify(pluginManager, never()).unload("slack", "1.0.0", false);
            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("pin it")));
        }

        @Test
        @DisplayName("keeps the old version loaded when executions are still inside it")
        void retainsBusyOldVersion() {
            catalogHas(offering("slack", "1.2.0", "ACTIVE", "1.0.0", "17", List.of("1.2.0", "1.0.0")));
            locally("slack", "1.0.0", "1.0.0");
            downloadYields("slack", "1.2.0");
            installYields("slack", "1.2.0", "SLACK_MESSAGE");
            when(pluginManager.unload("slack", "1.0.0", false))
                    .thenThrow(new PluginLoadException("slack:1.0.0", "2 invocations still in flight"));

            PluginInstallationService.InstallationResult result = service.update("slack");

            assertEquals(PluginInstallationService.InstallationResult.Outcome.UPDATED, result.outcome());
            assertTrue(result.previousVersionRetained());
            verify(pluginManager, never()).deactivate("slack", "1.0.0", "system");
        }

        @Test
        @DisplayName("does nothing when the registry offers nothing newer")
        void nothingNewer() {
            catalogHas(offering("slack", "1.0.0", "ACTIVE", "1.0.0", "17", List.of("1.0.0")));
            locally("slack", "1.0.0", "1.0.0");

            PluginInstallationService.InstallationResult result = service.update("slack");

            assertEquals(PluginInstallationService.InstallationResult.Outcome.ALREADY_CURRENT,
                    result.outcome());
            verify(downloader, never()).fetch(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("updating something not installed is refused")
        void notInstalled() {
            catalogHas(offering("slack", "1.2.0", "ACTIVE", "1.0.0", "17", List.of("1.2.0")));

            assertThrows(PluginNotFoundException.class, () -> service.update("slack"));
        }
    }

    // ------------------------------------------------------------------------------------------ uninstall

    @Nested
    @DisplayName("Uninstall")
    class Uninstall {

        @Test
        @DisplayName("unloads, deletes and forgets the version")
        void uninstalls() {
            locally("slack", "1.0.0", "1.0.0");

            PluginInstallationService.InstallationResult result = service.uninstall("slack", "1.0.0");

            assertEquals(PluginInstallationService.InstallationResult.Outcome.UNINSTALLED, result.outcome());
            assertNull(result.state());
            verify(pluginManager).unload("slack", "1.0.0", false);
            verify(pluginManager).delete("slack", "1.0.0", "system");
            verify(downloader).release("slack", "1.0.0");
            verify(installed).delete(any());
        }

        @Test
        @DisplayName("refuses while a published workflow uses the version, and names it")
        void refusesWhileUsed() {
            locally("slack", "1.0.0", "1.0.0");
            when(usage.dependents(eq("slack"), eq("1.0.0"), any(), anyBoolean())).thenReturn(List.of(
                    new PluginUsageService.Usage("w1", "Onboarding", "n1", "Notify", true)));

            InvalidWorkflowStateException refusal = assertThrows(InvalidWorkflowStateException.class,
                    () -> service.uninstall("slack", "1.0.0"));

            assertTrue(refusal.getMessage().contains("Onboarding"));
            verify(pluginManager, never()).unload(anyString(), anyString(), anyBoolean());
            verify(pluginManager, never()).delete(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("does not delete the plugin record while other versions remain")
        void keepsOtherVersions() {
            locally("slack", "1.2.0", "1.0.0", "1.2.0");

            service.uninstall("slack", "1.0.0");

            verify(installed, never()).delete(any());
            verify(installed, org.mockito.Mockito.atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("a version this engine does not have is a 404")
        void unknownVersion() {
            locally("slack", "1.0.0", "1.0.0");

            assertThrows(PluginNotFoundException.class, () -> service.uninstall("slack", "9.9.9"));
        }

        @Test
        @DisplayName("an in-flight execution refuses the unload rather than breaking it")
        void refusesWhileBusy() {
            locally("slack", "1.0.0", "1.0.0");
            when(pluginManager.unload("slack", "1.0.0", false))
                    .thenThrow(new PluginLoadException("slack:1.0.0", "1 invocation still in flight"));

            assertThrows(PluginLoadException.class, () -> service.uninstall("slack", "1.0.0"));

            verify(pluginManager, never()).delete(anyString(), anyString(), anyString());
        }
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Nested
    @DisplayName("Deactivate")
    class Deactivate {

        @Test
        @DisplayName("unloads without removing anything")
        void deactivates() {
            locally("slack", "1.2.0", "1.0.0", "1.2.0");

            PluginInstallationService.InstallationResult result = service.deactivate("slack", "1.0.0");

            assertEquals(PluginInstallationService.InstallationResult.Outcome.DEACTIVATED, result.outcome());
            assertEquals(InstallState.DISABLED, result.state());
            verify(pluginManager).deactivate("slack", "1.0.0", "system");
            verify(pluginManager, never()).delete(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("refuses while a published workflow depends on the version")
        void refusesWhileUsed() {
            locally("slack", "1.0.0", "1.0.0");
            when(usage.dependents(eq("slack"), eq("1.0.0"), any(), anyBoolean())).thenReturn(List.of(
                    new PluginUsageService.Usage("w1", "Onboarding", "n1", "Notify", false)));

            assertThrows(InvalidWorkflowStateException.class, () -> service.deactivate("slack", "1.0.0"));

            verify(pluginManager, never()).deactivate(anyString(), anyString(), anyString());
        }
    }
}
