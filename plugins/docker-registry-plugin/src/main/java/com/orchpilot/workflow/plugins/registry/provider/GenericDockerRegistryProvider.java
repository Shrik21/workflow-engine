package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

/**
 * Any OCI-compatible registry: Harbor, GitLab, GitHub Container Registry, Nexus, Artifactory, a plain
 * {@code registry:2}, or an internal one.
 *
 * <p>Deliberately the thinnest provider in the plugin — it adds a host and a credential and inherits everything
 * else. That it needs nothing more is the useful signal about the abstraction: the shared {@code /v2/} base
 * class really is the whole data plane, and the other providers are that plus an authentication dance.
 *
 * <p>Supports both credential styles registries use: a username and password/token (Basic, exchanged for a
 * bearer token when the registry challenges), or a pre-issued bearer token used directly.
 */
public class GenericDockerRegistryProvider extends AbstractRegistryProvider {

    private final String host;
    private final String username;
    private final String password;
    private final String bearerToken;

    public GenericDockerRegistryProvider(PluginHttpClient http, long timeoutMillis, String host,
                                         String username, String password, String bearerToken) {
        super(http, timeoutMillis);
        this.host = normalise(host);
        this.username = username;
        this.password = password;
        this.bearerToken = bearerToken;
    }

    private static String normalise(String host) {
        if (host == null || host.isBlank()) {
            throw new com.orchpilot.workflow.plugins.registry.exception.RegistryException(
                    "INVALID_REQUEST", "A generic registry needs its host, e.g. harbor.example.com.", false);
        }
        // Accept a pasted URL as well as a bare host; the base class builds https:// itself.
        return host.trim()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
    }

    @Override
    public RegistryProviderType type() {
        return RegistryProviderType.GENERIC;
    }

    @Override
    protected String registryHost() {
        return host;
    }

    @Override
    protected String basicAuthorization() {
        return username == null || username.isBlank() ? null : basic(username, password == null ? "" : password);
    }

    @Override
    protected String directAuthorization() {
        return bearerToken == null || bearerToken.isBlank() ? null : "Bearer " + bearerToken;
    }
}
