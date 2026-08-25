package com.orchpilot.workflow.model;

import java.util.ArrayList;
import java.util.List;

/**
 * What one plugin version is allowed to reach.
 *
 * <p>Declared at upload time by an administrator, not by the plugin, so a plugin cannot widen its own
 * permissions. Enforced by the {@code PluginContext} implementation the engine hands the plugin.
 *
 * <p>These are cooperative controls: they constrain a plugin that goes through the provided APIs. A
 * plugin that opens its own socket or reads its own file bypasses them, because a class loader is not
 * a sandbox. See the security section of the README for when to move plugins out of the JVM.
 */
public class PluginPermissions {

    /**
     * Hosts the plugin may call through {@code PluginHttpClient}. Supports a leading wildcard, e.g.
     * {@code *.sendgrid.com}. Empty falls back to the engine's default allowlist.
     */
    private List<String> allowedHosts = new ArrayList<>();

    /**
     * Secret name prefixes the plugin may read. {@code sendgrid.} permits {@code sendgrid.apiKey} and
     * nothing else. Empty denies all secret access.
     */
    private List<String> secretScopes = new ArrayList<>();

    /** Whether the plugin may use its namespaced document store. */
    private boolean dataStoreEnabled = true;

    /** Whether the plugin may publish business events. */
    private boolean eventsEnabled = true;

    /** Ceiling on any per-request HTTP timeout the plugin asks for; {@code null} uses the engine's. */
    private Long maxHttpTimeoutMillis;

    /** Ceiling on buffered HTTP response size; {@code null} uses the engine's. */
    private Long maxResponseBytes;

    public PluginPermissions() {
    }

    /**
     * @return permissions that allow nothing outward: no hosts, no secrets, no events
     */
    public static PluginPermissions restrictive() {
        PluginPermissions permissions = new PluginPermissions();
        permissions.dataStoreEnabled = true;
        permissions.eventsEnabled = false;
        return permissions;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts;
    }

    public List<String> getSecretScopes() {
        return secretScopes;
    }

    public void setSecretScopes(List<String> secretScopes) {
        this.secretScopes = secretScopes == null ? new ArrayList<>() : secretScopes;
    }

    public boolean isDataStoreEnabled() {
        return dataStoreEnabled;
    }

    public void setDataStoreEnabled(boolean dataStoreEnabled) {
        this.dataStoreEnabled = dataStoreEnabled;
    }

    public boolean isEventsEnabled() {
        return eventsEnabled;
    }

    public void setEventsEnabled(boolean eventsEnabled) {
        this.eventsEnabled = eventsEnabled;
    }

    public Long getMaxHttpTimeoutMillis() {
        return maxHttpTimeoutMillis;
    }

    public void setMaxHttpTimeoutMillis(Long maxHttpTimeoutMillis) {
        this.maxHttpTimeoutMillis = maxHttpTimeoutMillis;
    }

    public Long getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(Long maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }
}
