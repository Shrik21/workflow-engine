package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.model.WorkflowNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the registry is allowed to say about a publish.
 *
 * <p>The line these tests defend is which upstream state blocks and which merely informs. Revocation blocks
 * because it is the only one saying something already running may be harmful; deprecation and a newer local
 * version do not, because both describe a plugin that works, and a validator that refuses on either would make
 * every publish hostage to somebody else's release schedule.
 *
 * <p>The other half is silence. An engine with no registry, or one whose catalogue has never synced, must publish
 * exactly as it did before this class existed.
 */
class PluginPublishPolicyTest {

    private PluginCatalogSyncService catalog;
    private InstalledPluginRepository installed;
    private PluginPublishPolicy policy;

    @BeforeEach
    void setUp() {
        catalog = mock(PluginCatalogSyncService.class);
        installed = mock(InstalledPluginRepository.class);
        policy = new PluginPublishPolicy(catalog, installed);
        when(catalog.entry(anyString())).thenReturn(Optional.empty());
        when(installed.findById(anyString())).thenReturn(Optional.empty());
    }

    private static WorkflowNode node(String pluginId, String pluginVersion) {
        WorkflowNode node = new WorkflowNode();
        node.setId("notify");
        node.setType("SLACK_MESSAGE");
        node.setPluginId(pluginId);
        node.setPluginVersion(pluginVersion);
        return node;
    }

    private void catalogHas(String status, String versionStatus, String... versions) {
        List<CatalogRecords.CatalogVersion> rows = java.util.Arrays.stream(versions)
                .map(version -> new CatalogRecords.CatalogVersion(version, versionStatus, "1.0.0",
                        "checksum-" + version, 1024, List.of("SLACK_MESSAGE")))
                .toList();
        CatalogRecords.CatalogEntry entry = new CatalogRecords.CatalogEntry("slack", "Slack", "Posts messages.",
                "OrchPilot", versions.length == 0 ? null : versions[0], status, "1.0.0", "17", ">=1.0.0 <2.0.0",
                "checksum", List.of(), rows);
        when(catalog.entry("slack")).thenReturn(Optional.of(entry));
    }

    private void installedHas(String defaultVersion, String... versions) {
        InstalledPlugin plugin = new InstalledPlugin();
        plugin.setPluginId("slack");
        for (String version : versions) {
            plugin.put(new InstalledPlugin.InstalledVersion(version, InstallState.ACTIVE, "checksum", null,
                    1024, "1.0.0", List.of("SLACK_MESSAGE"), Map.of(), Map.of(), Instant.now(), "vivek",
                    Instant.now(), null));
        }
        plugin.setDefaultVersion(defaultVersion);
        when(installed.findById("slack")).thenReturn(Optional.of(plugin));
    }

    @Nested
    @DisplayName("Blocking")
    class Blocking {

        @Test
        @DisplayName("a revoked plugin blocks the publish")
        void revokedPlugin() {
            catalogHas("REVOKED", "ACTIVE", "1.0.0");

            List<String> errors = policy.errors(node("slack", "1.0.0"));

            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("revoked"));
        }

        @Test
        @DisplayName("a revoked version of an otherwise active plugin blocks the publish")
        void revokedVersion() {
            catalogHas("ACTIVE", "REVOKED", "1.0.0");

            assertTrue(policy.errors(node("slack", "1.0.0")).get(0).contains("revoked"));
        }

        @Test
        @DisplayName("a deprecated version does not block")
        void deprecatedDoesNotBlock() {
            catalogHas("ACTIVE", "DEPRECATED", "1.0.0");

            assertTrue(policy.errors(node("slack", "1.0.0")).isEmpty());
        }

        @Test
        @DisplayName("a node with no plugin coordinate is not this policy's business")
        void ignoresNonPluginNodes() {
            catalogHas("REVOKED", "ACTIVE", "1.0.0");

            assertTrue(policy.errors(node(null, null)).isEmpty());
            assertTrue(policy.warnings(node(null, null)).isEmpty());
        }
    }

    @Nested
    @DisplayName("Warning")
    class Warning {

        @Test
        @DisplayName("a deprecated pinned version is reported without blocking")
        void deprecatedPin() {
            catalogHas("ACTIVE", "DEPRECATED", "1.0.0");

            List<String> warnings = policy.warnings(node("slack", "1.0.0"));

            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("deprecated"));
        }

        @Test
        @DisplayName("a newer installed version is reported, with the pin explained rather than corrected")
        void newerInstalled() {
            catalogHas("ACTIVE", "ACTIVE", "1.1.0", "1.0.0");
            installedHas("1.1.0", "1.0.0", "1.1.0");

            List<String> warnings = policy.warnings(node("slack", "1.0.0"));

            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("1.1.0 is installed"));
            assertTrue(warnings.get(0).contains("honoured exactly"));
        }

        @Test
        @DisplayName("compares versions by precedence, so 1.10.0 outranks 1.9.0")
        void semanticOrdering() {
            catalogHas("ACTIVE", "ACTIVE", "1.10.0", "1.9.0");
            installedHas("1.10.0", "1.9.0", "1.10.0");

            assertEquals(1, policy.warnings(node("slack", "1.9.0")).size());
            // And the reverse pin says nothing: 1.9.0 is not newer than 1.10.0 by precedence, only by text.
            installedHas("1.9.0", "1.9.0");
            assertTrue(policy.warnings(node("slack", "1.10.0")).isEmpty());
        }

        @Test
        @DisplayName("an unpinned node draws no version warning, because it follows the default already")
        void unpinned() {
            catalogHas("ACTIVE", "DEPRECATED", "1.0.0");
            installedHas("1.0.0", "1.0.0");

            assertTrue(policy.warnings(node("slack", null)).isEmpty());
        }

        @Test
        @DisplayName("a revoked plugin warns nothing, because it is already an error")
        void revokedIsNotAlsoAWarning() {
            catalogHas("REVOKED", "ACTIVE", "1.0.0");

            assertTrue(policy.warnings(node("slack", "1.0.0")).isEmpty());
        }
    }

    @Nested
    @DisplayName("Silence")
    class Silence {

        @Test
        @DisplayName("an engine with no catalogue publishes exactly as before")
        void noCatalogue() {
            // Nothing stubbed: no registry configured, or never synced.
            assertTrue(policy.errors(node("slack", "1.0.0")).isEmpty());
            assertTrue(policy.warnings(node("slack", "1.0.0")).isEmpty());
        }

        @Test
        @DisplayName("a plugin the catalogue does not mention is left alone")
        void unknownPlugin() {
            catalogHas("ACTIVE", "ACTIVE", "1.0.0");

            assertTrue(policy.errors(node("restapi", "1.0.0")).isEmpty());
            assertTrue(policy.warnings(node("restapi", "1.0.0")).isEmpty());
        }

        @Test
        @DisplayName("a version neither side can order produces no advice")
        void unparseableVersion() {
            catalogHas("ACTIVE", "ACTIVE", "1.0.0");
            installedHas("nightly", "nightly");

            assertTrue(policy.warnings(node("slack", "1.0.0")).isEmpty());
        }
    }
}
