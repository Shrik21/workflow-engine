package com.orchpilot.workflow.pluginserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How this engine reaches the plugin registry.
 *
 * <p>Absent configuration is a supported state, not an error. An installation with no registry keeps working with
 * whatever plugins it already has; it simply cannot discover or install new ones. Refusing to start would make a
 * central service that the engine can survive the loss of into one it cannot start without, which is the opposite
 * of what splitting them was for.
 */
@ConfigurationProperties(prefix = "plugin.server")
public class PluginServerProperties {

    /** Base URL, for example {@code http://localhost:8085}. Blank disables every registry interaction. */
    private String baseUrl = "";

    private String clientId = "";

    /**
     * Client secret. Never logged, and never returned by any endpoint.
     *
     * <p>Belongs in an environment variable or a secret manager. It is a credential that can download every
     * plugin in the registry, which is executable code.
     */
    private String clientSecret = "";

    /** How often the catalogue is refreshed. */
    private Duration syncInterval = Duration.ofMinutes(5);

    /** How long to wait for the registry before giving up and using the cached catalogue. */
    private Duration requestTimeout = Duration.ofSeconds(20);

    /** Longer than a request timeout: downloading a fifty-megabyte archive is not a five-second operation. */
    private Duration downloadTimeout = Duration.ofMinutes(5);

    /** Where downloaded archives are cached before being loaded. */
    private String cacheDirectory = "plugin-cache";

    /** Whether to sync on startup. Off makes a test or an air-gapped installation predictable. */
    private boolean syncOnStartup = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        // A trailing slash here produces "//api/plugin-catalog", which some servers accept and some do not.
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret == null ? "" : clientSecret;
    }

    public Duration getSyncInterval() {
        return syncInterval;
    }

    public void setSyncInterval(Duration syncInterval) {
        this.syncInterval = syncInterval;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getDownloadTimeout() {
        return downloadTimeout;
    }

    public void setDownloadTimeout(Duration downloadTimeout) {
        this.downloadTimeout = downloadTimeout;
    }

    public String getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(String cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public boolean isSyncOnStartup() {
        return syncOnStartup;
    }

    public void setSyncOnStartup(boolean syncOnStartup) {
        this.syncOnStartup = syncOnStartup;
    }

    /**
     * @return whether a registry is configured at all. When false, the catalogue is whatever was last cached and
     *         installs are refused with a clear reason rather than attempted
     */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }

    /**
     * @return a description safe to log: the URL and client id, never the secret
     */
    public String describe() {
        if (baseUrl.isBlank()) {
            return "no plugin registry configured";
        }
        if (!isConfigured()) {
            return baseUrl + " (incomplete: a client id and secret are both required)";
        }
        return baseUrl + " as '" + clientId + "'";
    }
}
