package com.orchpilot.workflow.plugins.registry.model;

/**
 * The registry capabilities this plugin exposes — each one a workflow node type, an AI tool, and a risk level.
 *
 * <h2>One node per operation, provider chosen by configuration</h2>
 *
 * The alternative — a single "Registry Operation" node with an operation dropdown — cannot work here, because a
 * node carries exactly one risk flag: the AI Agent would be unable to tell a catalogue listing from a delete, and
 * the risk table below could not be enforced. Splitting by <em>operation</em> (not by provider) gives real
 * per-operation gating while keeping the palette at twelve nodes instead of sixty.
 *
 * <h2>Why push and pull are absent</h2>
 *
 * A plugin's HTTP client sends and receives {@code String} bodies under a size ceiling. Image layers are large
 * binary blobs, so pushing or pulling them is not expressible through the sanctioned plugin API at all — the
 * same isolation that makes third-party JARs safe to run. What <em>is</em> expressible is the whole metadata
 * plane below, plus {@link #RETAG} and {@link #COPY_TAG}, which move a manifest rather than its blobs and so
 * cover promotion and digest verification within a registry. Blob transfer belongs to a CI/CD step (a GitHub
 * Actions run this platform can already dispatch), not to an in-process plugin.
 */
public enum RegistryOperation {

    LOGIN("REGISTRY_LOGIN", "Registry Login / Test Connection",
            "container.registry.login",
            "Authenticates against the registry and reports whether the credentials work.",
            RiskLevel.READ_ONLY),

    LIST_REPOSITORIES("REGISTRY_LIST_REPOSITORIES", "List Registry Repositories",
            "container.registry.listRepositories",
            "Lists the repositories the credentials can see.",
            RiskLevel.READ_ONLY),

    CREATE_REPOSITORY("REGISTRY_CREATE_REPOSITORY", "Create Registry Repository",
            "container.registry.createRepository",
            "Creates a repository, where the provider's management API supports it.",
            RiskLevel.MEDIUM),

    DELETE_REPOSITORY("REGISTRY_DELETE_REPOSITORY", "Delete Registry Repository",
            "container.registry.deleteRepository",
            "Permanently deletes a repository and everything in it. Irreversible.",
            RiskLevel.HIGH),

    LIST_TAGS("REGISTRY_LIST_TAGS", "List Image Tags",
            "container.registry.listTags",
            "Lists the tags of one repository.",
            RiskLevel.READ_ONLY),

    LIST_IMAGES("REGISTRY_LIST_IMAGES", "List Images",
            "container.registry.listImages",
            "Lists a repository's images with their tags and digests.",
            RiskLevel.READ_ONLY),

    GET_IMAGE("REGISTRY_GET_IMAGE", "Get Image Metadata",
            "container.registry.getImage",
            "Reads one image's manifest metadata: digest, media type, size, architecture and OS.",
            RiskLevel.READ_ONLY),

    GET_MANIFEST("REGISTRY_GET_MANIFEST", "Get Image Manifest",
            "container.registry.getManifest",
            "Fetches the raw OCI/Docker manifest for a tag or digest.",
            RiskLevel.READ_ONLY),

    GET_DIGEST("REGISTRY_GET_DIGEST", "Get Image Digest",
            "container.registry.getDigest",
            "Resolves a tag to its immutable sha256 digest.",
            RiskLevel.READ_ONLY),

    EXISTS("REGISTRY_IMAGE_EXISTS", "Check Image Exists",
            "container.registry.exists",
            "Checks whether a tag or digest exists, without downloading anything.",
            RiskLevel.READ_ONLY),

    SEARCH("REGISTRY_SEARCH", "Search Images",
            "container.registry.search",
            "Searches the registry's catalogue, where the provider supports search.",
            RiskLevel.READ_ONLY),

    RETAG("REGISTRY_RETAG", "Retag Image",
            "container.registry.retag",
            "Adds a new tag to an existing image by re-putting its manifest. Moves no blobs, so it is fast and "
                    + "byte-identical — the digest is unchanged.",
            RiskLevel.MEDIUM),

    COPY_TAG("REGISTRY_COPY_TAG", "Promote Image Within Registry",
            "container.registry.copyTag",
            "Copies an image's manifest from one repository to another in the same registry, for dev to test to "
                    + "production promotion. Verifies the digest is preserved.",
            RiskLevel.MEDIUM),

    DELETE_IMAGE("REGISTRY_DELETE_IMAGE", "Delete Image / Tag",
            "container.registry.deleteImage",
            "Deletes an image manifest by digest, removing the tags that point at it. Irreversible.",
            RiskLevel.HIGH);

    /**
     * How consequential an operation is. Mapped onto the node's {@code destructive} flag so the existing agent
     * approval policy gates the dangerous ones; carried verbatim in the plugin manifest so a policy engine can
     * apply finer rules than a boolean.
     */
    public enum RiskLevel {
        READ_ONLY,
        LOW,
        MEDIUM,
        HIGH
    }

    private final String nodeType;
    private final String displayName;
    private final String capability;
    private final String description;
    private final RiskLevel risk;

    RegistryOperation(String nodeType, String displayName, String capability, String description,
                      RiskLevel risk) {
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.capability = capability;
        this.description = description;
        this.risk = risk;
    }

    public String nodeType() {
        return nodeType;
    }

    public String displayName() {
        return displayName;
    }

    /** @return the namespaced capability id the AI Agent discovers this operation by */
    public String capability() {
        return capability;
    }

    public String description() {
        return description;
    }

    public RiskLevel risk() {
        return risk;
    }

    /** @return whether a supervised agent must have this approved before it runs */
    public boolean destructive() {
        return risk == RiskLevel.HIGH;
    }

    public static RegistryOperation forNodeType(String nodeType) {
        for (RegistryOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
