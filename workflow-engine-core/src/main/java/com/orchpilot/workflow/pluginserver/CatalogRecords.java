package com.orchpilot.workflow.pluginserver;

import java.util.List;
import java.util.Map;

/**
 * The registry's catalogue, as this engine reads it.
 *
 * <h2>Why these are declared here and not shared</h2>
 *
 * <p>The registry has its own {@code PluginCatalogEntry} and these mirror it, which looks like duplication worth
 * removing by putting the type in the SDK. It is not. This is the wire contract between two independently
 * deployable services, and a shared class makes them one deployable in practice: the registry could not add a
 * field without recompiling the engine, and an engine running an older release would fail to deserialise a payload
 * it should have ignored.
 *
 * <p>Restating the shape here is what lets the two versions drift safely. Unknown fields are ignored, absent ones
 * are null, and this engine reads the subset it understands.
 */
public final class CatalogRecords {

    private CatalogRecords() {
    }

    /**
     * One plugin as the registry offers it.
     *
     * @param pluginId            stable id
     * @param name                display name
     * @param description         one line
     * @param vendor              publisher
     * @param latestVersion       newest active release, or null when the plugin has only pre-releases
     * @param status              the plugin's availability in the registry
     * @param sdkVersion          SDK the latest version was built against
     * @param javaVersion         Java version the latest version needs
     * @param engineCompatibility engine version range, or null when unconstrained
     * @param checksum            SHA-256 of the latest version's archive
     * @param nodes               node types the latest version contributes
     * @param versions            every published version, newest first
     */
    public record CatalogEntry(
            String pluginId,
            String name,
            String description,
            String vendor,
            String latestVersion,
            String status,
            String sdkVersion,
            String javaVersion,
            String engineCompatibility,
            String checksum,
            List<CatalogNode> nodes,
            List<CatalogVersion> versions) {

        public CatalogEntry {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            versions = versions == null ? List.of() : List.copyOf(versions);
        }

        /**
         * @param version the version to look for
         * @return that version's row, or empty when the registry no longer publishes it
         */
        public java.util.Optional<CatalogVersion> version(String version) {
            return versions.stream().filter(row -> row.version().equals(version)).findFirst();
        }

        /** @return whether the registry has withdrawn this plugin entirely */
        public boolean isRevoked() {
            return "REVOKED".equals(status);
        }

        /** @return whether the registry has superseded this plugin */
        public boolean isDeprecated() {
            return "DEPRECATED".equals(status);
        }
    }

    /**
     * One installable version.
     *
     * @param version    semantic version
     * @param status     ACTIVE or DEPRECATED
     * @param sdkVersion SDK it was built against
     * @param checksum   SHA-256 of its archive, which an installer verifies against
     * @param fileSize   archive size, for progress reporting
     * @param nodeTypes  node types it contributes
     */
    public record CatalogVersion(String version, String status, String sdkVersion, String checksum,
                                 long fileSize, List<String> nodeTypes) {

        public CatalogVersion {
            nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
        }

        public boolean isDeprecated() {
            return "DEPRECATED".equals(status);
        }
    }

    /**
     * A node type, with everything a designer needs to render it.
     *
     * @param nodeType            identifier a workflow node references
     * @param displayName         palette label
     * @param description         one line of help
     * @param category            palette grouping
     * @param icon                icon name
     * @param configurationSchema schema the property panel renders from
     * @param inputPorts          named inputs
     * @param outputPorts         named outputs
     */
    public record CatalogNode(String nodeType, String displayName, String description, String category,
                              String icon, Map<String, Object> configurationSchema,
                              List<String> inputPorts, List<String> outputPorts) {

        public CatalogNode {
            configurationSchema = configurationSchema == null ? Map.of() : Map.copyOf(configurationSchema);
            inputPorts = inputPorts == null ? List.of() : List.copyOf(inputPorts);
            outputPorts = outputPorts == null ? List.of() : List.copyOf(outputPorts);
        }
    }
}
