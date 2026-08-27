package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.model.PluginVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One installed plugin version as returned by the API.
 *
 * <p>Includes the granted permissions on purpose: an operator reviewing what is installed needs to see what
 * each version is allowed to reach, not only that it is active. Settings are included, permissions are
 * included, and secret values never are.
 *
 * @param pluginId      plugin id
 * @param version       version
 * @param name          display name
 * @param description   what it does
 * @param pluginType    extension category
 * @param status        {@code INSTALLED}, {@code ACTIVE}, {@code INACTIVE}, {@code FAILED} or {@code DELETED}
 * @param mainClass     implementation class
 * @param apiVersion    SDK version it was built against
 * @param jarFileName   original upload file name
 * @param jarSizeBytes  archive size
 * @param sha256        checksum of the stored bytes
 * @param signed        whether the archive is signed
 * @param nodeTypes     node types it contributes
 * @param allowedHosts  hosts it may call
 * @param secretScopes  secret prefixes it may read
 * @param eventsEnabled whether it may publish events
 * @param settings      installation-scoped configuration
 * @param dependencies  declared external dependencies
 * @param loaded        whether it is loaded in this instance right now
 * @param activeCalls   invocations currently in flight
 * @param totalCalls    invocations since it was loaded
 * @param failedCalls   failed invocations since it was loaded
 * @param uploadedAt    upload time
 * @param uploadedBy    who uploaded it
 * @param lastLoadedAt  last successful load
 * @param loadError     why the last load failed
 */
public record PluginVersionResponse(String pluginId, String version, String name, String description,
                                    String pluginType, String status, String mainClass, int apiVersion,
                                    String jarFileName, long jarSizeBytes, String sha256, boolean signed,
                                    List<String> nodeTypes, List<String> allowedHosts,
                                    List<String> secretScopes, boolean eventsEnabled,
                                    Map<String, Object> settings, List<String> dependencies, boolean loaded,
                                    int activeCalls, long totalCalls, long failedCalls, Instant uploadedAt,
                                    String uploadedBy, Instant lastLoadedAt, String loadError) {

    /**
     * @param version persistence model
     * @return the API representation with runtime counters zeroed
     */
    public static PluginVersionResponse from(PluginVersion version) {
        return from(version, false, 0, 0, 0);
    }

    /**
     * @param version     persistence model
     * @param loaded      whether it is loaded now
     * @param activeCalls in-flight invocations
     * @param totalCalls  invocations since load
     * @param failedCalls failed invocations since load
     * @return the API representation
     */
    public static PluginVersionResponse from(PluginVersion version, boolean loaded, int activeCalls,
                                             long totalCalls, long failedCalls) {
        return new PluginVersionResponse(version.getPluginId(), version.getVersion(), version.getName(),
                version.getDescription(), version.getPluginType(), String.valueOf(version.getStatus()),
                version.getMainClass(), version.getApiVersion(), version.getJarFileName(),
                version.getJarSizeBytes(), version.getSha256(), version.isSigned(), version.getNodeTypes(),
                version.getPermissions().getAllowedHosts(), version.getPermissions().getSecretScopes(),
                version.getPermissions().isEventsEnabled(), version.getSettings(), version.getDependencies(),
                loaded, activeCalls, totalCalls, failedCalls, version.getUploadedAt(),
                version.getUploadedBy(), version.getLastLoadedAt(), version.getLoadError());
    }
}
