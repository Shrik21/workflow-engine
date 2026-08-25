package com.orchpilot.workflow.plugins.registry.model;

/**
 * The container registries this plugin can talk to.
 *
 * <p>Adding one is a new {@code ContainerRegistryProvider} implementation plus a constant here — no change to
 * the node catalogue, the dispatch path, or anything the AI Agent sees, because every node takes the provider
 * as configuration rather than baking it into the node type.
 */
public enum RegistryProviderType {

    DOCKER_HUB("Docker Hub"),
    AWS_ECR("AWS Elastic Container Registry"),
    AZURE_ACR("Azure Container Registry"),
    GOOGLE_ARTIFACT_REGISTRY("Google Artifact Registry"),
    GENERIC("Generic / OCI-compatible registry");

    private final String displayName;

    RegistryProviderType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static RegistryProviderType parse(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
