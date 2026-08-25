package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.model.PluginMetadata;
import com.orchpilot.workflow.model.PluginVersion;

import java.time.Instant;
import java.util.List;

/**
 * A plugin as returned by the API.
 *
 * @param id             plugin id
 * @param name           display name
 * @param description    what it does
 * @param pluginType     {@code NODE}, {@code ACTION} or {@code TRIGGER}
 * @param status         status of the plugin as a whole
 * @param latestVersion  most recently uploaded version
 * @param defaultVersion version serving nodes that do not pin one
 * @param versions       every installed version
 * @param createdAt      first install time
 * @param updatedAt      last change time
 */
public record PluginResponse(String id, String name, String description, String pluginType, String status,
                             String latestVersion, String defaultVersion, List<PluginVersionResponse> versions,
                             Instant createdAt, Instant updatedAt) {

    /**
     * @param head     plugin head document
     * @param versions installed versions
     * @return the API representation
     */
    public static PluginResponse from(PluginMetadata head, List<PluginVersion> versions) {
        return new PluginResponse(head.getId(), head.getName(), head.getDescription(), head.getPluginType(),
                String.valueOf(head.getStatus()), head.getLatestVersion(), head.getDefaultVersion(),
                versions.stream().map(PluginVersionResponse::from).toList(),
                head.getCreatedAt(), head.getUpdatedAt());
    }
}
