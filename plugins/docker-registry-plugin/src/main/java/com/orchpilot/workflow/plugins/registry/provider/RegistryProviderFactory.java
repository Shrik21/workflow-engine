package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

/**
 * Builds the right {@link ContainerRegistryProvider} for a node's configuration.
 *
 * <h2>Where credentials come from, and where they do not</h2>
 *
 * A node stores the <em>name</em> of a secret, never a credential. The value is read here, at execution time,
 * through the engine's scoped secret provider — which audits the access and registers the value for log
 * redaction — and is handed straight to a provider that keeps it in a field for the length of one call. Nothing
 * in this path puts a credential into node configuration, node output, a workflow variable, a log line, or
 * anything the AI Agent can see.
 *
 * <p>Composite credentials (an AWS key pair, an Azure service principal) are stored as one secret in a
 * {@code key:value} or JSON form rather than as several fields, so that a workflow author references exactly one
 * secret name and cannot half-configure an identity.
 */
public final class RegistryProviderFactory {

    private RegistryProviderFactory() {
    }

    /**
     * @param configuration the node's resolved configuration
     * @param context       the plugin context, for secrets and the HTTP client
     * @param timeoutMillis the execution's time budget
     * @return a provider ready to use
     */
    public static ContainerRegistryProvider create(NodeConfiguration configuration, PluginContext context,
                                                   long timeoutMillis) {
        RegistryProviderType type = RegistryProviderType.parse(configuration.getString("provider", "GENERIC"));
        String secretName = configuration.getString("credentialsSecret", null);
        String secret = secretName == null || secretName.isBlank() ? null : context.secrets().require(secretName);

        return switch (type) {
            case DOCKER_HUB -> {
                String[] pair = split(secret, "Docker Hub");
                yield new DockerHubRegistryProvider(context.http(), timeoutMillis,
                        configuration.getString("username", pair[0]), pair[1]);
            }
            case AWS_ECR -> {
                String[] pair = split(secret, "AWS ECR");
                yield new AwsEcrRegistryProvider(context.http(), timeoutMillis,
                        configuration.getString("accountId", null),
                        configuration.getString("region", null),
                        pair[0], pair[1],
                        configuration.getString("sessionToken", null));
            }
            case AZURE_ACR -> {
                String[] pair = split(secret, "Azure ACR");
                boolean servicePrincipal = configuration.has("clientId");
                yield new AzureAcrRegistryProvider(context.http(), timeoutMillis,
                        configuration.getString("registryName", null),
                        configuration.getString("tenantId", null),
                        servicePrincipal ? configuration.getString("clientId", pair[0]) : null,
                        servicePrincipal ? pair[1] : null,
                        servicePrincipal ? null : pair[0],
                        servicePrincipal ? null : pair[1]);
            }
            case GOOGLE_ARTIFACT_REGISTRY -> new GoogleArtifactRegistryProvider(context.http(), timeoutMillis,
                    configuration.getString("projectId", null),
                    configuration.getString("location", null),
                    configuration.getString("repositoryScope", null),
                    secret);
            case GENERIC -> {
                String[] pair = secret == null ? new String[]{null, null} : splitLenient(secret);
                yield new GenericDockerRegistryProvider(context.http(), timeoutMillis,
                        configuration.getString("registryUrl", null),
                        configuration.getString("username", pair[0]),
                        pair[1],
                        configuration.getString("bearerToken", null));
            }
        };
    }

    /**
     * Splits a {@code user:password} secret. Required for providers where an identity is inherently two parts —
     * a half-supplied credential is a misconfiguration worth naming rather than a mysterious 401 later.
     */
    private static String[] split(String secret, String provider) {
        if (secret == null || !secret.contains(":")) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    provider + " needs its credentials stored as 'identity:secret' in one secret.", false);
        }
        return splitLenient(secret);
    }

    private static String[] splitLenient(String secret) {
        int colon = secret.indexOf(':');
        return colon < 0
                ? new String[]{secret.trim(), ""}
                : new String[]{secret.substring(0, colon).trim(), secret.substring(colon + 1).trim()};
    }
}
