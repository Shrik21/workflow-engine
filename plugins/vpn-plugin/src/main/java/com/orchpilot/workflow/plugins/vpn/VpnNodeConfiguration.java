package com.orchpilot.workflow.plugins.vpn;

import com.orchpilot.workflow.plugins.vpn.spi.VpnConnectionRequest;
import com.orchpilot.workflow.plugins.vpn.spi.VpnProvider;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Turns a node's configuration into a resolved {@link VpnConnectionRequest}.
 *
 * <h2>Credentials come from the secret store and only from there</h2>
 *
 * There is no field on this node that holds an access key, a pre-shared key or a private key. A provider
 * declares the credential names it needs ({@code accessKeyId}, {@code presharedKey}, …); this resolves each
 * one from the secret store, by one of two routes an operator chooses, and hands the provider the values in a
 * map that never touches the workflow definition:
 *
 * <ol>
 *   <li>a {@code connectionProfile} (or {@code credentialId}) naming a stored credential whose parts are the
 *       secrets {@code <profile>.<name>} — one name in the workflow, every value in the store; this is the
 *       "select a connection instead of entering credentials on every node" the spec asks for;</li>
 *   <li>a {@code credentialSecrets} map of credential name to secret name, for the case where the secrets are
 *       not grouped under one profile.</li>
 * </ol>
 *
 * <p>Because a raw credential can be written nowhere, there is nothing to refuse and nothing to redact — the
 * value exists only for the length of one execution, inside the request, and a provider is contracted never to
 * log it.
 */
final class VpnNodeConfiguration {

    private VpnNodeConfiguration() {
    }

    /**
     * Builds the request.
     *
     * @param operation     the operation, already parsed and validated
     * @param provider      the provider, so its credential names are known
     * @param configuration the node configuration
     * @param resolve       the engine's variable resolver
     * @param secrets       the scoped secret provider
     * @return the resolved request
     */
    static VpnConnectionRequest build(String operation, VpnProvider provider, NodeConfiguration configuration,
                                      UnaryOperator<String> resolve, SecretProvider secrets) {
        Map<String, Object> settings = resolvedSettings(configuration, resolve);
        Map<String, String> credentials = resolveCredentials(provider, configuration, secrets);

        String connectionId = resolve.apply(configuration.getString("connectionId", "")).trim();
        String region = resolve.apply(configuration.getString("region", "")).trim();

        return new VpnConnectionRequest(operation, provider.id(), connectionId, region, settings, credentials);
    }

    /**
     * Every non-credential configuration value, with variables resolved.
     *
     * <p>The known control fields are dropped so a provider sees only its own settings, and every string is
     * passed through the resolver so {@code ${cloud.region}} and {@code ${vpn.connectionId}} work anywhere.
     */
    private static Map<String, Object> resolvedSettings(NodeConfiguration configuration,
                                                        UnaryOperator<String> resolve) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : configuration.asMap().entrySet()) {
            String key = entry.getKey();
            if (CONTROL_FIELDS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            settings.put(key, value instanceof String text ? resolve.apply(text) : value);
        }
        return settings;
    }

    /** Resolves each credential the provider declares, from a profile prefix and/or an explicit map. */
    private static Map<String, String> resolveCredentials(VpnProvider provider, NodeConfiguration configuration,
                                                          SecretProvider secrets) {
        Map<String, String> resolved = new LinkedHashMap<>();

        String profile = configuration.getString("connectionProfile",
                configuration.getString("credentialId", "")).trim();

        @SuppressWarnings("unchecked")
        Map<String, Object> explicit = configuration.find("credentialSecrets")
                .filter(value -> value instanceof Map)
                .map(value -> (Map<String, Object>) value)
                .orElse(Map.of());

        for (String name : provider.credentialNames()) {
            // An explicit secret name wins over the profile prefix, so a single credential can be overridden
            // without moving the rest.
            if (explicit.get(name) != null) {
                secrets.find(String.valueOf(explicit.get(name)).trim())
                        .ifPresent(value -> resolved.put(name, value));
            } else if (!profile.isBlank()) {
                secrets.find(profile + "." + name).ifPresent(value -> resolved.put(name, value));
            }
        }
        return resolved;
    }

    /**
     * Fields consumed by the node itself rather than a provider.
     *
     * <p>{@code outputVariable} is deliberately <em>not</em> here: the node reads it back out of the resolved
     * settings when it names the output, and excluding it would leave the name unfindable. It is a harmless
     * extra setting a provider ignores.
     */
    private static final java.util.Set<String> CONTROL_FIELDS = java.util.Set.of(
            "provider", "operation", "connectionId", "region",
            "connectionProfile", "credentialId", "credentialSecrets",
            "waitUntilConnected", "timeoutSeconds", "pollIntervalSeconds");
}
