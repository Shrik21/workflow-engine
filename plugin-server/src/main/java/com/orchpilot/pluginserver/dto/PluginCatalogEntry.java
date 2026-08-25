package com.orchpilot.pluginserver.dto;

import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginNode;
import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One plugin as a workflow service sees it.
 *
 * <h2>Why the versions are included</h2>
 *
 * <p>The obvious catalogue entry carries only the latest version, and it is not enough. A workflow service has to
 * answer three questions from one sync: what is newest (to offer an update), does the exact version an existing
 * workflow pins still exist (to validate a publish), and what does a node look like (to draw a palette). With only
 * the latest version, the second question needs a request per pinned version, on every sync, from every service.
 *
 * <p>So the entry carries a compact row per published version and the node metadata of the latest. That keeps the
 * payload proportional to the number of versions rather than to their contents: schemas, dependencies and
 * requested permissions are only present for the latest, and a client that needs them for an older version asks
 * for that version directly.
 *
 * @param pluginId      stable id
 * @param name          display name
 * @param description   one line
 * @param vendor        who publishes it
 * @param latestVersion the newest active release, which is what an unpinned install resolves to
 * @param status        the plugin's availability
 * @param sdkVersion    SDK the latest version was built against, for the compatibility check
 * @param javaVersion   Java version the latest version needs
 * @param engineCompatibility engine version range the latest version declares, or null
 * @param checksum      SHA-256 of the latest version's archive
 * @param nodes         node types the latest version contributes, with their configuration schemas
 * @param versions      every published version, newest first
 */
public record PluginCatalogEntry(
        String pluginId,
        String name,
        String description,
        String vendor,
        String latestVersion,
        PluginStatus status,
        String sdkVersion,
        String javaVersion,
        String engineCompatibility,
        String checksum,
        List<PluginNode> nodes,
        List<CatalogVersion> versions) {

    public PluginCatalogEntry {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    /**
     * One installable version, without its contents.
     *
     * <p>The checksum is here because it is what an installer verifies, and because it doubles as a cheap
     * identity: a workflow service comparing its installed checksum against this one can tell that a version it
     * holds is the version the registry holds, without downloading anything.
     *
     * @param version    semantic version
     * @param status     ACTIVE or DEPRECATED; nothing else is published
     * @param sdkVersion SDK it was built against
     * @param checksum   SHA-256 of its archive
     * @param fileSize   archive size, so an installer can show progress
     * @param nodeTypes  node types it contributes, names only
     */
    public record CatalogVersion(String version, PluginStatus status, String sdkVersion, String checksum,
                                 long fileSize, List<String> nodeTypes) {

        public CatalogVersion {
            nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
        }

        static CatalogVersion from(PluginVersion version) {
            return new CatalogVersion(version.getVersion(), version.getStatus(), version.getSdkVersion(),
                    version.getChecksum(), version.getFileSize(), version.nodeTypes());
        }

        /** @return whether this version is superseded and should not be chosen for a new workflow */
        public boolean isDeprecated() {
            return status == PluginStatus.DEPRECATED;
        }
    }

    /**
     * Assembles an entry.
     *
     * @param plugin    the plugin head
     * @param published its published versions, in any order
     * @return the entry, or null when nothing about it is installable
     */
    public static PluginCatalogEntry of(Plugin plugin, List<PluginVersion> published) {
        if (published.isEmpty()) {
            return null;
        }
        List<CatalogVersion> versions = published.stream()
                .sorted(Comparator.comparing(PluginVersion::getVersion,
                        Comparator.comparing(com.orchpilot.workflow.sdk.version.SemanticVersion::parse).reversed()))
                .map(CatalogVersion::from)
                .toList();

        // The latest version supplies the node metadata and compatibility facts. When the head names no latest,
        // which happens while every version is deprecated, the newest published one stands in: a client still
        // needs to know what the nodes look like to render anything at all.
        PluginVersion detail = published.stream()
                .filter(candidate -> candidate.getVersion().equals(plugin.getLatestVersion()))
                .findFirst()
                .orElseGet(() -> published.stream()
                        .max(Comparator.comparing(PluginVersion::getVersion,
                                Comparator.comparing(
                                        com.orchpilot.workflow.sdk.version.SemanticVersion::parse)))
                        .orElseThrow());

        return new PluginCatalogEntry(plugin.getPluginId(), plugin.getName(), plugin.getDescription(),
                plugin.getVendor(), plugin.getLatestVersion(), plugin.getStatus(), detail.getSdkVersion(),
                detail.getJavaVersion(), detail.getEngineCompatibility(), detail.getChecksum(),
                detail.getNodes(), versions);
    }

    /**
     * A stable fingerprint of everything a client would react to.
     *
     * <p>Feeds the catalogue's ETag. Deliberately excludes anything that changes without meaning anything, such as
     * an updated timestamp on the plugin head: an ETag that changes on every sync is an ETag that saves nothing.
     *
     * @return text that changes exactly when this entry meaningfully changes
     */
    public String fingerprint() {
        StringBuilder text = new StringBuilder(pluginId).append('|')
                .append(latestVersion).append('|').append(status).append('|').append(name);
        for (CatalogVersion version : versions) {
            text.append('|').append(version.version()).append(':').append(version.status())
                    .append(':').append(version.checksum());
        }
        // Node types matter to a palette, so a version that changed its contributed nodes must invalidate.
        for (PluginNode node : nodes) {
            text.append('#').append(node.nodeType()).append(':')
                    .append(schemaFingerprint(node.configurationSchema()));
        }
        return text.toString();
    }

    /**
     * A schema's size and keys, rather than the schema itself.
     *
     * <p>Enough to notice that a property was added or removed, which is what a designer's property panel would
     * render differently, without serialising a nested document into the fingerprint on every request.
     */
    private static String schemaFingerprint(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return "0";
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            return schema.size() + "." + map.size() + "." + map.keySet();
        }
        return String.valueOf(schema.size());
    }
}
