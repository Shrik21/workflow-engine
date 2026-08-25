package com.orchpilot.workflow.plugins.registry;

import com.orchpilot.workflow.plugins.registry.model.RegistryOperation;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * The designer form for each node.
 *
 * <h2>How the provider-specific UI happens</h2>
 *
 * The engine's designer renders whatever a node declares here — there is no per-plugin Angular code to write,
 * which is what lets a new plugin appear in the palette without rebuilding the console. Every node therefore
 * carries the union of the provider fields and names, in each field's title, which provider it belongs to
 * ("AWS ECR — account id"). An author fills in the group matching the provider they picked, and the AI Agent
 * gets one honest schema listing everything that could be required.
 */
final class RegistrySchemas {

    private RegistrySchemas() {
    }

    static Map<String, Object> forOperation(RegistryOperation operation) {
        SchemaBuilder schema = connection();

        switch (operation) {
            case LOGIN, LIST_REPOSITORIES -> { /* connection fields are enough */ }

            case SEARCH -> schema.string("query", "Search query", true)
                    .integer("limit", "Maximum results", false).withDefault("limit", 25);

            case CREATE_REPOSITORY, DELETE_REPOSITORY, LIST_TAGS, LIST_IMAGES ->
                    schema.string("repository", "Repository", true)
                            .withDescription("repository", "For example team/myapp, or just myapp.");

            case GET_IMAGE, GET_MANIFEST, GET_DIGEST, EXISTS, DELETE_IMAGE ->
                    schema.string("image", "Image reference", true)
                            .withDescription("image",
                                    "team/myapp:1.4.0, or team/myapp@sha256:… to address it immutably. "
                                            + "A registry host in the reference is ignored; the connection decides.");

            case RETAG -> schema.string("image", "Source image", true)
                    .withDescription("image", "The image to add a tag to, e.g. team/myapp:1.4.0.")
                    .string("newTag", "New tag", true);

            case COPY_TAG -> schema.string("image", "Source image", true)
                    .withDescription("image", "The image to promote, e.g. dev/myapp:1.4.0.")
                    .string("targetRepository", "Target repository", true)
                    .withDescription("targetRepository",
                            "Must be in the same registry — promotion re-points a manifest and moves no layers.")
                    .string("targetTag", "Target tag", true)
                    .bool("verifyDigest", "Fail if the digest changes", false)
                    .withDefault("verifyDigest", true);
        }
        return schema.build();
    }

    /** Connection fields, shared by every node: the provider, its credential, and its addressing. */
    private static SchemaBuilder connection() {
        return SchemaBuilder.object()
                .select("provider", "Registry provider", List.of(
                        "DOCKER_HUB", "AWS_ECR", "AZURE_ACR", "GOOGLE_ARTIFACT_REGISTRY", "GENERIC"), true)
                .withDefault("provider", "DOCKER_HUB")
                .secretRef("credentialsSecret", "Credentials secret name", true)
                .withDescription("credentialsSecret",
                        "The NAME of a secret (prefix registry.), never a credential. Docker Hub and generic: "
                                + "'username:token'. ECR: 'accessKeyId:secretAccessKey'. ACR: "
                                + "'clientId:clientSecret' or 'adminUser:password'. Artifact Registry: the "
                                + "service-account JSON.")

                // Docker Hub / generic
                .string("username", "Docker Hub / Generic — username", false)
                .string("registryUrl", "Generic — registry host", false)
                .withDescription("registryUrl", "For example harbor.example.com or ghcr.io.")
                .string("bearerToken", "Generic — pre-issued bearer token", false)

                // AWS ECR
                .string("accountId", "AWS ECR — account id", false)
                .string("region", "AWS ECR — region", false)
                .withDescription("region", "For example us-east-1.")
                .string("sessionToken", "AWS ECR — session token (temporary credentials)", false)

                // Azure ACR
                .string("registryName", "Azure ACR — registry name", false)
                .withDescription("registryName", "myregistry, or myregistry.azurecr.io.")
                .string("tenantId", "Azure ACR — tenant id", false)
                .string("clientId", "Azure ACR — service principal client id", false)
                .withDescription("clientId", "Leave blank to use an admin username and password instead.")

                // Google Artifact Registry
                .string("projectId", "Artifact Registry — project id", false)
                .string("location", "Artifact Registry — location", false)
                .withDescription("location", "For example us-central1.")
                .string("repositoryScope", "Artifact Registry — repository", false)
                .withDescription("repositoryScope",
                        "The Artifact Registry repository that contains the images.");
    }
}
