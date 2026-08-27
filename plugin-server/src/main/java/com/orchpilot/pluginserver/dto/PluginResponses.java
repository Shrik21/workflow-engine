package com.orchpilot.pluginserver.dto;

import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginDependency;
import com.orchpilot.pluginserver.model.PluginNode;
import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the registry API returns.
 *
 * <p>Records rather than the documents themselves, for one reason that matters more than tidiness: a
 * {@link PluginVersion} carries {@code fileId}, which is where the bytes are in this service's storage. That is
 * an implementation detail of the registry, and a client that learned it would be a client that could be built to
 * depend on it. Downloads go through the download endpoint, which is the contract.
 */
public final class PluginResponses {

    private PluginResponses() {
    }

    /**
     * A plugin in a list.
     *
     * @param pluginId      stable id, never contains a version
     * @param name          display name
     * @param description   one line
     * @param vendor        who publishes it
     * @param pluginType    extension category
     * @param latestVersion newest active release, or null when it has none
     * @param status        availability
     * @param versionCount  how many versions exist, in any state
     * @param createdAt     first upload
     * @param updatedAt     last change
     */
    public record PluginSummary(
            String pluginId,
            String name,
            String description,
            String vendor,
            String pluginType,
            String latestVersion,
            PluginStatus status,
            int versionCount,
            Instant createdAt,
            Instant updatedAt) {

        public static PluginSummary from(Plugin plugin) {
            return new PluginSummary(plugin.getPluginId(), plugin.getName(), plugin.getDescription(),
                    plugin.getVendor(), plugin.getPluginType(), plugin.getLatestVersion(),
                    plugin.getStatus(), plugin.getVersionCount(), plugin.getCreatedAt(),
                    plugin.getUpdatedAt());
        }
    }

    /**
     * One version's full record.
     *
     * <p>{@code requestedPermissions}, not granted ones. What this plugin is allowed to do is decided by the
     * workflow service that installs it; this is only what its author asked for, and an operator reviewing an
     * install wants to see the request.
     *
     * @param pluginId            owning plugin
     * @param version             semantic version
     * @param status              lifecycle state
     * @param name                display name at this version
     * @param description         description at this version
     * @param mainClass           entry point the workflow service instantiates
     * @param sdkVersion          SDK the plugin was built against
     * @param javaVersion         Java version it requires
     * @param engineCompatibility engine version range, or null
     * @param checksum            SHA-256 the downloader must reproduce
     * @param fileName            suggested download name
     * @param fileSize            archive size in bytes
     * @param signed              whether the archive carries signature files
     * @param nodes               node types contributed
     * @param dependencies        libraries declared
     * @param requestedPermissions what the plugin asks to be allowed
     * @param uploadedAt          when it arrived
     * @param uploadedBy          who uploaded it
     * @param publishedAt         when it became active, or null
     * @param revocationReason    why it was revoked, or null
     */
    public record PluginVersionDetail(
            String pluginId,
            String version,
            PluginStatus status,
            String name,
            String description,
            String mainClass,
            String sdkVersion,
            String javaVersion,
            String engineCompatibility,
            String checksum,
            String fileName,
            long fileSize,
            boolean signed,
            List<PluginNode> nodes,
            List<PluginDependency> dependencies,
            Map<String, Object> requestedPermissions,
            Instant uploadedAt,
            String uploadedBy,
            Instant publishedAt,
            String revocationReason) {

        public static PluginVersionDetail from(PluginVersion version) {
            return new PluginVersionDetail(version.getPluginId(), version.getVersion(), version.getStatus(),
                    version.getName(), version.getDescription(), version.getMainClass(),
                    version.getSdkVersion(), version.getJavaVersion(), version.getEngineCompatibility(),
                    version.getChecksum(), version.getFileName(), version.getFileSize(), version.isSigned(),
                    version.getNodes(), version.getDependencies(), version.getRequestedPermissions(),
                    version.getUploadedAt(), version.getUploadedBy(), version.getPublishedAt(),
                    version.getRevocationReason());
        }
    }

    /**
     * A version in a list. No nodes and no schemas, which are the bulk of a version record.
     *
     * @param pluginId owning plugin
     * @param version  semantic version
     * @param status   lifecycle state
     * @param sdkVersion SDK it was built against
     * @param checksum SHA-256
     * @param fileSize archive size
     * @param nodeTypes node types contributed, names only
     * @param uploadedAt when it arrived
     * @param uploadedBy who uploaded it
     */
    public record PluginVersionSummary(
            String pluginId,
            String version,
            PluginStatus status,
            String sdkVersion,
            String checksum,
            long fileSize,
            List<String> nodeTypes,
            Instant uploadedAt,
            String uploadedBy) {

        public static PluginVersionSummary from(PluginVersion version) {
            return new PluginVersionSummary(version.getPluginId(), version.getVersion(), version.getStatus(),
                    version.getSdkVersion(), version.getChecksum(), version.getFileSize(),
                    version.nodeTypes(), version.getUploadedAt(), version.getUploadedBy());
        }
    }

    /**
     * The answer to an upload.
     *
     * <p>Says what was published and, when the version landed in DRAFT, what remains to be done. An upload that
     * silently produces something invisible to every workflow service is an upload that will be reported as a bug.
     *
     * @param pluginId   the plugin
     * @param version    the version
     * @param status     what state it is in
     * @param checksum   SHA-256 of what was stored
     * @param nodeTypes  node types it contributes
     * @param nextStep   what an operator should do next, or null when nothing is needed
     */
    public record PluginUploadResult(
            String pluginId,
            String version,
            PluginStatus status,
            String checksum,
            List<String> nodeTypes,
            String nextStep) {

        public static PluginUploadResult from(PluginVersion version) {
            String nextStep = version.getStatus() == PluginStatus.DRAFT
                    ? "Publish it with POST /api/plugins/" + version.getPluginId() + "/versions/"
                            + version.getVersion() + "/publish before a workflow service can install it."
                    : null;
            return new PluginUploadResult(version.getPluginId(), version.getVersion(), version.getStatus(),
                    version.getChecksum(), version.nodeTypes(), nextStep);
        }
    }
}
