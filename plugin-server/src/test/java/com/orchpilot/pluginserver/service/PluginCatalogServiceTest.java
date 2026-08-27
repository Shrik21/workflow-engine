package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.dto.PluginCatalogEntry;
import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginNode;
import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;
import com.orchpilot.pluginserver.repository.PluginVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The catalogue: what it contains, and when its validator changes.
 *
 * <p>The conditional-request logic is unit-tested here because verifying it over HTTP took three attempts to get
 * the client's quoting right, and none of those attempts told me anything about the server. A validator comparison
 * is pure logic and belongs in a test that cannot be defeated by a shell.
 */
class PluginCatalogServiceTest {

    private PluginService plugins;
    private PluginVersionRepository versions;
    private PluginCatalogService catalog;

    @BeforeEach
    void setUp() {
        plugins = mock(PluginService.class);
        versions = mock(PluginVersionRepository.class);
        catalog = new PluginCatalogService(plugins, versions);
    }

    private static Plugin plugin(String id, String latest) {
        Plugin plugin = new Plugin();
        plugin.setPluginId(id);
        plugin.setName(id + " plugin");
        plugin.setLatestVersion(latest);
        plugin.setStatus(PluginStatus.ACTIVE);
        return plugin;
    }

    private static PluginVersion version(String id, String version, PluginStatus status, String checksum) {
        PluginVersion record = new PluginVersion();
        record.setId(PluginVersion.idOf(id, version));
        record.setPluginId(id);
        record.setVersion(version);
        record.setStatus(status);
        record.setChecksum(checksum);
        record.setSdkVersion("1.0.0");
        record.setUploadedAt(Instant.now());
        record.setNodes(List.of(new PluginNode("SENDGRID_EMAIL", "Send Email", null, "Communication",
                "email", Map.of("type", "object", "properties", Map.of("to", Map.of())), List.of(), List.of())));
        return record;
    }

    @Nested
    @DisplayName("Assembly")
    class Assembly {

        @Test
        @DisplayName("carries every published version, newest first, with the latest version's nodes")
        void carriesVersionsAndNodes() {
            when(plugins.catalogue()).thenReturn(List.of(plugin("sendgrid", "1.10.0")));
            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("sendgrid", "1.9.0", PluginStatus.ACTIVE, "aaa"),
                    version("sendgrid", "1.10.0", PluginStatus.ACTIVE, "bbb"),
                    version("sendgrid", "1.0.0", PluginStatus.DEPRECATED, "ccc")));

            PluginCatalogEntry entry = catalog.catalog().entries().get(0);

            // Newest first, and by precedence rather than by text: 1.10.0 above 1.9.0.
            assertEquals(List.of("1.10.0", "1.9.0", "1.0.0"),
                    entry.versions().stream().map(PluginCatalogEntry.CatalogVersion::version).toList());
            assertEquals("1.10.0", entry.latestVersion());
            assertEquals("bbb", entry.checksum(), "the detail fields come from the latest version");
            assertEquals(1, entry.nodes().size());
            assertTrue(entry.versions().get(2).isDeprecated());
        }

        @Test
        @DisplayName("a deprecated version is still offered, because a pinned workflow needs it")
        void deprecatedVersionsAreOffered() {
            when(plugins.catalogue()).thenReturn(List.of(plugin("sendgrid", "1.2.0")));
            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("sendgrid", "1.2.0", PluginStatus.ACTIVE, "aaa"),
                    version("sendgrid", "1.0.0", PluginStatus.DEPRECATED, "bbb")));

            assertEquals(2, catalog.catalog().entries().get(0).versions().size());
        }

        @Test
        @DisplayName("a plugin whose only version is deprecated still appears, as deprecated")
        void deprecatedOnlyVersionStillAppears() {
            // The head has no latestVersion, because latest excludes deprecated releases. The plugin must
            // still reach the catalogue: a workflow service pinned to that version is running it right now and
            // is entitled to be told it is deprecated. Excluding it made the engine report the plugin as
            // unknown to the registry, which is a different and misleading thing.
            Plugin head = plugin("restapi", null);
            when(plugins.catalogue()).thenReturn(List.of(head));
            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("restapi", "1.0.0", PluginStatus.DEPRECATED, "aaa")));

            PluginCatalogEntry entry = catalog.catalog().entries().get(0);

            assertEquals("restapi", entry.pluginId());
            assertEquals(1, entry.versions().size());
            assertTrue(entry.versions().get(0).isDeprecated());
            // The node metadata still has to come from somewhere, or a client can render nothing at all.
            assertEquals(1, entry.nodes().size());
        }

        @Test
        @DisplayName("a plugin claiming a latest version with nothing published is omitted, not broken")
        void omitsInconsistentPlugin() {
            when(plugins.catalogue()).thenReturn(List.of(plugin("ghost", "1.0.0")));
            when(versions.findByStatusIn(any())).thenReturn(List.of());

            // Rather than emitting an entry a client cannot install anything from.
            assertTrue(catalog.catalog().entries().isEmpty());
        }

        @Test
        @DisplayName("entries are ordered by plugin id, so two syncs can be diffed")
        void ordersById() {
            when(plugins.catalogue()).thenReturn(List.of(
                    plugin("slack", "1.0.0"), plugin("restapi", "1.0.0"), plugin("sendgrid", "1.0.0")));
            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("slack", "1.0.0", PluginStatus.ACTIVE, "a"),
                    version("restapi", "1.0.0", PluginStatus.ACTIVE, "b"),
                    version("sendgrid", "1.0.0", PluginStatus.ACTIVE, "c")));

            assertEquals(List.of("restapi", "sendgrid", "slack"),
                    catalog.catalog().entries().stream().map(PluginCatalogEntry::pluginId).toList());
        }

        @Test
        @DisplayName("an empty registry produces an empty catalogue with a usable tag")
        void emptyRegistry() {
            when(plugins.catalogue()).thenReturn(List.of());

            PluginCatalogService.Catalog snapshot = catalog.catalog();

            assertTrue(snapshot.entries().isEmpty());
            assertTrue(snapshot.etag().startsWith("\""), "even an empty catalogue is cacheable");
        }
    }

    @Nested
    @DisplayName("The validator")
    class Validator {

        @Test
        @DisplayName("is stable across calls when nothing changed")
        void stableWhenUnchanged() {
            when(plugins.catalogue()).thenReturn(List.of(plugin("sendgrid", "1.0.0")));
            when(versions.findByStatusIn(any()))
                    .thenReturn(List.of(version("sendgrid", "1.0.0", PluginStatus.ACTIVE, "aaa")));

            // An ETag that changed on every request would make every conditional request a miss, which is
            // worse than having no ETag at all: the cost without the benefit.
            assertEquals(catalog.catalog().etag(), catalog.catalog().etag());
        }

        @Test
        @DisplayName("changes when a version is published, deprecated or re-checksummed")
        void changesWhenContentChanges() {
            when(plugins.catalogue()).thenReturn(List.of(plugin("sendgrid", "1.0.0")));
            when(versions.findByStatusIn(any()))
                    .thenReturn(List.of(version("sendgrid", "1.0.0", PluginStatus.ACTIVE, "aaa")));
            String before = catalog.catalog().etag();

            when(plugins.catalogue()).thenReturn(List.of(plugin("sendgrid", "1.2.0")));
            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("sendgrid", "1.0.0", PluginStatus.ACTIVE, "aaa"),
                    version("sendgrid", "1.2.0", PluginStatus.ACTIVE, "bbb")));
            assertNotEquals(before, catalog.catalog().etag());

            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("sendgrid", "1.0.0", PluginStatus.DEPRECATED, "aaa"),
                    version("sendgrid", "1.2.0", PluginStatus.ACTIVE, "bbb")));
            String afterDeprecation = catalog.catalog().etag();

            when(versions.findByStatusIn(any())).thenReturn(List.of(
                    version("sendgrid", "1.0.0", PluginStatus.DEPRECATED, "aaa"),
                    version("sendgrid", "1.2.0", PluginStatus.ACTIVE, "changed")));
            assertNotEquals(afterDeprecation, catalog.catalog().etag(),
                    "a rebuilt archive under the same version must invalidate a client's copy");
        }

        @Test
        @DisplayName("matches an exact tag, a weakened one, a list, and a wildcard")
        void matchesEveryLegalForm() {
            PluginCatalogService.Catalog snapshot =
                    new PluginCatalogService.Catalog(List.of(), "\"abc123\"", Instant.now());

            assertTrue(snapshot.matches("\"abc123\""));
            // A proxy may weaken a strong validator in transit; treating that as a miss would transfer the
            // whole catalogue again for no reason.
            assertTrue(snapshot.matches("W/\"abc123\""));
            assertTrue(snapshot.matches("\"other\", \"abc123\""));
            assertTrue(snapshot.matches("*"));
        }

        @Test
        @DisplayName("does not match a stale tag, an absent header, or a tag that merely looks similar")
        void refusesEverythingElse() {
            PluginCatalogService.Catalog snapshot =
                    new PluginCatalogService.Catalog(List.of(), "\"abc123\"", Instant.now());

            assertFalse(snapshot.matches("\"stale\""));
            assertFalse(snapshot.matches(null));
            assertFalse(snapshot.matches(""));
            assertFalse(snapshot.matches("abc123"), "an unquoted tag is not a valid validator");
        }
    }
}
