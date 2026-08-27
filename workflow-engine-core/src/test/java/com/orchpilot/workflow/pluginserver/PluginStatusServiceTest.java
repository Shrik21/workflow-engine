package com.orchpilot.workflow.pluginserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.repository.PluginVersionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The comparison the marketplace is built on.
 *
 * <p>Six statuses, and the order they are decided in is the design: a revoked version outranks everything because it
 * is the only one saying something already running may be harmful, and an incompatible plugin must not be offered an
 * update that cannot load. These tests pin that order, because it is the kind of logic that looks arbitrary later and
 * gets "simplified" into the wrong answer.
 */
class PluginStatusServiceTest {

    private PluginCatalogSyncService catalog;
    private InstalledPluginRepository installed;
    private PluginStatusService service;
    private PluginVersionRepository engineVersions;

    @BeforeEach
    void setUp() {
        catalog = mock(PluginCatalogSyncService.class);
        installed = mock(InstalledPluginRepository.class);
        engineVersions = mock(PluginVersionRepository.class);
        service = new PluginStatusService(catalog, installed, new PluginCompatibilityService(), engineVersions);
        when(installed.findAllByOrderByPluginIdAsc()).thenReturn(List.of());
        when(catalog.entries()).thenReturn(List.of());
    }

    @Test
    void directUploadsAppearInListAndDetailWithoutRegistryRecords() {
        PluginVersion version = new PluginVersion();
        version.setPluginId("slack");
        version.setVersion("1.0.1");
        version.setName("Slack");
        version.setStatus(PluginStatus.ACTIVE);
        version.setNodeTypes(List.of("SLACK_MESSAGE"));
        when(engineVersions.findAll()).thenReturn(List.of(version));
        when(engineVersions.findByPluginIdOrderByUploadedAtDesc("slack")).thenReturn(List.of(version));
        var row = service.statuses().get(0);
        assertEquals("slack", row.pluginId());
        assertEquals("1.0.1", row.installedVersion());
        assertEquals(PluginSyncStatus.UNKNOWN_TO_REGISTRY, row.status());
        assertEquals(List.of("SLACK_MESSAGE"), row.nodeTypes());
        assertEquals(row, service.status("slack").orElseThrow());
        org.mockito.Mockito.verify(installed, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletedDirectUploadsAreNotListed() {
        PluginVersion version = new PluginVersion();
        version.setPluginId("deleted");
        version.setVersion("1.0.0");
        version.setStatus(PluginStatus.DELETED);
        when(engineVersions.findAll()).thenReturn(List.of(version));
        when(engineVersions.findByPluginIdOrderByUploadedAtDesc("deleted")).thenReturn(List.of(version));
        assertTrue(service.statuses().isEmpty());
        assertTrue(service.status("deleted").isEmpty());
    }

    private static CatalogRecords.CatalogEntry offering(String id, String latest, String status,
                                                       String sdk, List<String> versions) {
        return new CatalogRecords.CatalogEntry(id, id + " plugin", "does things", "OrchPilot", latest, status,
                sdk, "17", ">=1.0.0 <2.0.0", "checksum-" + latest,
                List.of(new CatalogRecords.CatalogNode(id.toUpperCase() + "_NODE", "Node", null,
                        "Communication", "icon", Map.of(), List.of(), List.of())),
                versions.stream()
                        .map(version -> new CatalogRecords.CatalogVersion(version, "ACTIVE", sdk,
                                "checksum-" + version, 1024, List.of()))
                        .toList());
    }

    private static InstalledPlugin locally(String id, String... versions) {
        InstalledPlugin plugin = new InstalledPlugin();
        plugin.setPluginId(id);
        plugin.setName(id + " plugin");
        for (String version : versions) {
            plugin.put(new InstalledPlugin.InstalledVersion(version, InstallState.ACTIVE,
                    "checksum-" + version, id + "/" + version, 1024, "1.0.0", List.of(),
                    Map.of(), Map.of(), Instant.now(), "vivek", Instant.now(), null));
        }
        plugin.setDefaultVersion(versions.length == 0 ? null : versions[versions.length - 1]);
        return plugin;
    }

    private PluginSyncStatus statusOf(String pluginId) {
        return service.status(pluginId).orElseThrow().status();
    }

    @Nested
    @DisplayName("The six states")
    class States {

        @Test
        @DisplayName("offered and absent here is NOT_INSTALLED")
        void notInstalled() {
            when(catalog.entries()).thenReturn(List.of(
                    offering("slack", "1.0.0", "ACTIVE", "1.0.0", List.of("1.0.0"))));
            when(catalog.entry("slack")).thenReturn(java.util.Optional.of(
                    offering("slack", "1.0.0", "ACTIVE", "1.0.0", List.of("1.0.0"))));

            assertEquals(PluginSyncStatus.NOT_INSTALLED, statusOf("slack"));
            assertTrue(statusOf("slack").isInstallable());
        }

        @Test
        @DisplayName("installed at the newest offered version is INSTALLED")
        void installed() {
            when(catalog.entry("sendgrid")).thenReturn(java.util.Optional.of(
                    offering("sendgrid", "1.2.0", "ACTIVE", "1.0.0", List.of("1.2.0", "1.0.0"))));
            when(installed.findById("sendgrid")).thenReturn(
                    java.util.Optional.of(locally("sendgrid", "1.2.0")));

            assertEquals(PluginSyncStatus.INSTALLED, statusOf("sendgrid"));
        }

        @Test
        @DisplayName("installed behind the newest offered version is UPDATE_AVAILABLE")
        void updateAvailable() {
            when(catalog.entry("sendgrid")).thenReturn(java.util.Optional.of(
                    offering("sendgrid", "1.2.0", "ACTIVE", "1.0.0", List.of("1.2.0", "1.0.0"))));
            when(installed.findById("sendgrid")).thenReturn(
                    java.util.Optional.of(locally("sendgrid", "1.0.0")));

            PluginStatusService.PluginStatusView view = service.status("sendgrid").orElseThrow();

            assertEquals(PluginSyncStatus.UPDATE_AVAILABLE, view.status());
            assertEquals("1.0.0", view.installedVersion());
            assertEquals("1.2.0", view.serverVersion());
        }

        @Test
        @DisplayName("a version this engine cannot run is INCOMPATIBLE, installed or not")
        void incompatible() {
            when(catalog.entry("future")).thenReturn(java.util.Optional.of(
                    offering("future", "3.0.0", "ACTIVE", "2.0.0", List.of("3.0.0"))));

            PluginStatusService.PluginStatusView view = service.status("future").orElseThrow();

            assertEquals(PluginSyncStatus.INCOMPATIBLE, view.status());
            assertFalse(view.compatible());
            assertTrue(view.incompatibility().stream().anyMatch(reason -> reason.contains("SDK 2.x")),
                    () -> "the reason must be readable: " + view.incompatibility());
            // Never offered for install, because the install could only fail.
            assertFalse(view.status().isInstallable());
        }

        @Test
        @DisplayName("a revoked plugin that is installed here is REVOKED, and stays running")
        void revoked() {
            when(catalog.entry("bad")).thenReturn(java.util.Optional.of(
                    offering("bad", "1.0.0", "REVOKED", "1.0.0", List.of("1.0.0"))));
            when(installed.findById("bad")).thenReturn(java.util.Optional.of(locally("bad", "1.0.0")));

            assertEquals(PluginSyncStatus.REVOKED, statusOf("bad"));
            assertTrue(statusOf("bad").needsAttention());
        }

        @Test
        @DisplayName("a revoked plugin that is not installed is simply not offered")
        void revokedAndAbsent() {
            when(catalog.entry("bad")).thenReturn(java.util.Optional.of(
                    offering("bad", "1.0.0", "REVOKED", "1.0.0", List.of("1.0.0"))));

            assertEquals(PluginSyncStatus.NOT_INSTALLED, statusOf("bad"));
        }

        @Test
        @DisplayName("installed here and unknown to the catalogue is UNKNOWN_TO_REGISTRY, not missing")
        void unknownToRegistry() {
            when(installed.findById("legacy")).thenReturn(
                    java.util.Optional.of(locally("legacy", "1.0.0")));

            // This is what a plugin installed before the registry existed looks like, and what one the registry
            // has dropped looks like. Either way it must stay visible: it is running.
            assertEquals(PluginSyncStatus.UNKNOWN_TO_REGISTRY, statusOf("legacy"));
        }

        @Test
        @DisplayName("a deprecated offering that is installed is DEPRECATED, not an update")
        void deprecated() {
            when(catalog.entry("old")).thenReturn(java.util.Optional.of(
                    offering("old", "1.0.0", "DEPRECATED", "1.0.0", List.of("1.0.0"))));
            when(installed.findById("old")).thenReturn(java.util.Optional.of(locally("old", "1.0.0")));

            assertEquals(PluginSyncStatus.DEPRECATED, statusOf("old"));
        }
    }

    @Nested
    @DisplayName("Precedence between states")
    class Precedence {

        @Test
        @DisplayName("revocation outranks an available update")
        void revocationBeatsUpdate() {
            when(catalog.entry("bad")).thenReturn(java.util.Optional.of(
                    offering("bad", "2.0.0", "REVOKED", "1.0.0", List.of("2.0.0", "1.0.0"))));
            when(installed.findById("bad")).thenReturn(java.util.Optional.of(locally("bad", "1.0.0")));

            // Offering an update to a revoked plugin would be advising somebody to install withdrawn code.
            assertEquals(PluginSyncStatus.REVOKED, statusOf("bad"));
        }

        @Test
        @DisplayName("incompatibility outranks an available update")
        void incompatibilityBeatsUpdate() {
            when(catalog.entry("future")).thenReturn(java.util.Optional.of(
                    offering("future", "2.0.0", "ACTIVE", "2.0.0", List.of("2.0.0", "1.0.0"))));
            when(installed.findById("future")).thenReturn(
                    java.util.Optional.of(locally("future", "1.0.0")));

            assertEquals(PluginSyncStatus.INCOMPATIBLE, statusOf("future"));
        }

        @Test
        @DisplayName("1.10.0 offered against 1.9.0 installed is an update, not the reverse")
        void comparesNumerically() {
            when(catalog.entry("sendgrid")).thenReturn(java.util.Optional.of(
                    offering("sendgrid", "1.10.0", "ACTIVE", "1.0.0", List.of("1.10.0", "1.9.0"))));
            when(installed.findById("sendgrid")).thenReturn(
                    java.util.Optional.of(locally("sendgrid", "1.9.0")));

            assertEquals(PluginSyncStatus.UPDATE_AVAILABLE, statusOf("sendgrid"));
        }

        @Test
        @DisplayName("several installed versions report the newest usable one")
        void reportsNewestInstalled() {
            when(catalog.entry("sendgrid")).thenReturn(java.util.Optional.of(
                    offering("sendgrid", "1.2.0", "ACTIVE", "1.0.0", List.of("1.2.0", "1.0.0"))));
            InstalledPlugin local = locally("sendgrid", "1.0.0", "1.2.0");
            when(installed.findById("sendgrid")).thenReturn(java.util.Optional.of(local));

            PluginStatusService.PluginStatusView view = service.status("sendgrid").orElseThrow();

            // Both versions are installed on purpose, for workflows pinned to each. The status is about the
            // newest; the list carries both.
            assertEquals(PluginSyncStatus.INSTALLED, view.status());
            assertEquals("1.2.0", view.installedVersion());
            assertEquals(List.of("1.2.0", "1.0.0"),
                    view.installedVersions().stream()
                            .map(PluginStatusService.InstalledVersionView::version).toList());
        }

        @Test
        @DisplayName("a failed install does not count as installed")
        void failedInstallIsNotInstalled() {
            InstalledPlugin local = new InstalledPlugin();
            local.setPluginId("broken");
            local.setName("broken plugin");
            local.put(new InstalledPlugin.InstalledVersion("1.0.0", InstallState.INSTALL_FAILED,
                    "c", "p", 1, "1.0.0", List.of(), Map.of(), Map.of(), Instant.now(), "vivek", null,
                    "checksum mismatch"));
            when(catalog.entry("broken")).thenReturn(java.util.Optional.of(
                    offering("broken", "1.0.0", "ACTIVE", "1.0.0", List.of("1.0.0"))));
            when(installed.findById("broken")).thenReturn(java.util.Optional.of(local));

            assertEquals(PluginSyncStatus.NOT_INSTALLED, statusOf("broken"));
        }
    }

    @Nested
    @DisplayName("The list")
    class Listing {

        @Test
        @DisplayName("covers the union of both sides and puts what needs attention first")
        void unionOrderedByAttention() {
            when(catalog.entries()).thenReturn(List.of(
                    offering("slack", "1.0.0", "ACTIVE", "1.0.0", List.of("1.0.0")),
                    offering("sendgrid", "1.2.0", "ACTIVE", "1.0.0", List.of("1.2.0", "1.0.0"))));
            when(installed.findAllByOrderByPluginIdAsc()).thenReturn(List.of(
                    locally("sendgrid", "1.0.0"), locally("legacy", "1.0.0")));

            List<PluginStatusService.PluginStatusView> views = service.statuses();

            assertEquals(3, views.size(), "a plugin only this engine has must not disappear");
            assertEquals(PluginSyncStatus.UPDATE_AVAILABLE, views.get(0).status());
            assertEquals("sendgrid", views.get(0).pluginId());
        }
    }
}
