package com.orchpilot.workflow.plugin;

import java.util.List;
import java.util.Map;

/**
 * Everything an operator supplies when installing a plugin, beyond the JAR itself.
 *
 * <p>Permissions are here rather than in the JAR on purpose: a plugin must not be able to declare what it
 * is allowed to reach. The hosts it may call and the secrets it may read are an operator decision, checked
 * against what the plugin's documentation claims to need.
 *
 * @param pluginId       expected plugin id; when set it must match what the plugin reports
 * @param version        expected version; when set it must match what the plugin reports
 * @param mainClass      implementation class, used only when the JAR declares none
 * @param description    operator-supplied description, overriding the plugin's own
 * @param allowedHosts   hosts the plugin may call through the provided HTTP client
 * @param secretScopes   secret name prefixes the plugin may read
 * @param settings       installation-scoped, non-secret configuration
 * @param dependencies   declared external dependencies, recorded for review
 * @param expectedSha256 checksum the uploader expects the bytes to have
 * @param activate       whether to load and activate immediately after installation
 * @param eventsEnabled  whether the plugin may publish business events
 * @param actor          who is performing the upload, for the audit trail
 */
public record PluginUploadRequest(String pluginId, String version, String mainClass, String description,
                                 List<String> allowedHosts, List<String> secretScopes,
                                 Map<String, Object> settings, List<String> dependencies,
                                 String expectedSha256, boolean activate, boolean eventsEnabled,
                                 String actor) {

    /**
     * @param actor who is performing the upload
     * @return a request with no expectations and default permissions, activating on install
     */
    public static PluginUploadRequest minimal(String actor) {
        return new PluginUploadRequest(null, null, null, null, List.of(), List.of(), Map.of(), List.of(),
                null, true, true, actor);
    }
}
