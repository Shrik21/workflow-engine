package com.orchpilot.workflow.plugins.registry;

import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.ImageReference;
import com.orchpilot.workflow.plugins.registry.model.RegistryOperation;
import com.orchpilot.workflow.plugins.registry.provider.ContainerRegistryProvider;
import com.orchpilot.workflow.plugins.registry.provider.RegistryProviderFactory;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One capability model over Docker Hub, AWS ECR, Azure ACR, Google Artifact Registry and any OCI-compatible
 * registry.
 *
 * <h2>One node per operation, provider chosen in configuration</h2>
 *
 * Each {@link RegistryOperation} is its own node type, so the AI Agent sees a distinct tool with a distinct risk
 * level — reads run freely while deletes are gated by the platform's existing approval policy. The provider is a
 * dropdown on every node rather than a node-type suffix, which keeps the palette at fourteen nodes instead of
 * seventy and means a workflow can be repointed from ECR to ACR by changing one field.
 *
 * <h2>What it does not do</h2>
 *
 * Image push and pull are absent by design, not omission: a plugin's HTTP client carries {@code String} bodies
 * under a size ceiling, so multi-hundred-megabyte layer blobs cannot pass through it. That is the same isolation
 * that makes third-party plugins safe to run in-process. Blob transfer belongs to a CI/CD step — one this
 * platform can dispatch through the GitHub plugin — while everything expressible over the registry API,
 * including digest verification and manifest-level promotion, lives here.
 */
public class RegistryPlugin implements WorkflowNodePlugin {

    private static final String PLUGIN_ID = "orchpilot-docker-registry";
    private static final String PLUGIN_VERSION = "1.0.2";
    private static final String CATEGORY = "Container Registry";

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Docker Registry";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Manage container images across Docker Hub, AWS ECR, Azure ACR, Google Artifact Registry and any "
                + "OCI-compatible registry through one capability model.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("Docker Registry plugin initialised with {} operations",
                RegistryOperation.values().length);
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("Docker Registry plugin destroyed");
        }
    }

    // ------------------------------------------------------------------ node catalogue

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>();
        for (RegistryOperation operation : RegistryOperation.values()) {
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .description(operation.description() + "  [capability: " + operation.capability() + "]")
                    .category(CATEGORY)
                    .icon("container")
                    .configurationSchema(RegistrySchemas.forOperation(operation))
                    .outputVariables("success", "provider", "registry", "repository", "image", "tag", "digest",
                            "operation", "message", "metadata")
                    .idempotent(operation.risk() == RegistryOperation.RiskLevel.READ_ONLY)
                    .supportsRetry(true)
                    .supportsAI(true)
                    .destructive(operation.destructive())
                    .build());
        }
        return definitions;
    }

    // ------------------------------------------------------------------ execution

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        RegistryOperation operation = RegistryOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("REGISTRY_UNKNOWN_OPERATION",
                    "Unknown registry node type: " + executionContext.nodeType());
        }
        NodeConfiguration cfg = executionContext.configuration();
        Instant started = Instant.now();

        try {
            ContainerRegistryProvider provider =
                    RegistryProviderFactory.create(cfg, context, executionContext.timeoutMillis());
            Map<String, Object> outputs = dispatch(operation, provider, cfg);
            outputs.put("success", true);
            outputs.put("operation", operation.name());
            outputs.put("provider", provider.type().name());

            long millis = java.time.Duration.between(started, Instant.now()).toMillis();
            context.logger().info("Registry {} on {} completed in {} ms", operation, provider.type(), millis);
            audit(executionContext, operation, provider.type().name(), cfg, "SUCCESS", millis);
            return NodeExecutionResult.success(outputs);

        } catch (RegistryException ex) {
            long millis = java.time.Duration.between(started, Instant.now()).toMillis();
            context.logger().warn("Registry {} failed: {} ({})", operation, ex.errorCode(), ex.getMessage());
            audit(executionContext, operation, cfg.getString("provider", "?"), cfg, "FAILED", millis);
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure("REGISTRY_MISCONFIGURED", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return NodeExecutionResult.failure("INVALID_IMAGE", ex.getMessage());
        }
    }

    private Map<String, Object> dispatch(RegistryOperation operation, ContainerRegistryProvider provider,
                                         NodeConfiguration cfg) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        switch (operation) {
            case LOGIN -> outputs.putAll(provider.login());

            case LIST_REPOSITORIES -> {
                List<String> repositories = provider.listRepositories();
                outputs.put("repositories", repositories);
                outputs.put("count", repositories.size());
            }

            case LIST_TAGS -> {
                String repository = cfg.requireString("repository");
                List<String> tags = provider.listTags(repository);
                outputs.put("repository", repository);
                outputs.put("tags", tags);
                outputs.put("count", tags.size());
            }

            case LIST_IMAGES -> {
                String repository = cfg.requireString("repository");
                List<Map<String, Object>> images = provider.listImages(repository);
                outputs.put("repository", repository);
                outputs.put("images", images);
                outputs.put("count", images.size());
            }

            case GET_IMAGE -> outputs.putAll(provider.getImage(image(cfg)));

            case GET_MANIFEST -> {
                ImageReference image = image(cfg);
                Map<String, Object> manifest = provider.getManifest(image);
                outputs.put("repository", image.repository());
                outputs.put("tag", image.tag());
                outputs.put("digest", manifest.get("digest"));
                outputs.put("manifest", manifest);
            }

            case GET_DIGEST -> {
                ImageReference image = image(cfg);
                String digest = provider.getDigest(image);
                outputs.put("repository", image.repository());
                outputs.put("tag", image.tag());
                outputs.put("digest", digest);
            }

            case EXISTS -> {
                ImageReference image = image(cfg);
                boolean exists = provider.exists(image);
                outputs.put("repository", image.repository());
                outputs.put("tag", image.tag());
                outputs.put("exists", exists);
                outputs.put("message", exists ? "The image exists." : "The image does not exist.");
            }

            case SEARCH -> {
                List<Map<String, Object>> hits = provider.search(cfg.requireString("query"),
                        cfg.getInt("limit", 25));
                outputs.put("results", hits);
                outputs.put("count", hits.size());
            }

            case CREATE_REPOSITORY -> {
                String repository = cfg.requireString("repository");
                provider.createRepository(repository);
                outputs.put("repository", repository);
                outputs.put("message", "Repository created.");
            }

            case DELETE_REPOSITORY -> {
                String repository = cfg.requireString("repository");
                provider.deleteRepository(repository);
                outputs.put("repository", repository);
                outputs.put("message", "Repository deleted.");
            }

            case RETAG -> {
                ImageReference image = image(cfg);
                String newTag = cfg.requireString("newTag");
                String digest = provider.retag(image, newTag);
                outputs.put("repository", image.repository());
                outputs.put("tag", newTag);
                outputs.put("digest", digest);
                outputs.put("message", "Tagged " + image + " as " + newTag + " (digest unchanged).");
            }

            case COPY_TAG -> {
                ImageReference image = image(cfg);
                String targetRepository = cfg.requireString("targetRepository");
                String targetTag = cfg.requireString("targetTag");
                String digest = provider.copyTag(image, targetRepository, targetTag);
                outputs.put("repository", targetRepository);
                outputs.put("tag", targetTag);
                outputs.put("digest", digest);
                outputs.put("sourceRepository", image.repository());
                outputs.put("message", "Promoted to " + targetRepository + ":" + targetTag
                        + " with digest " + digest + ".");
            }

            case DELETE_IMAGE -> {
                ImageReference image = image(cfg);
                provider.deleteImage(image);
                outputs.put("repository", image.repository());
                outputs.put("tag", image.tag());
                outputs.put("message", "Image deleted.");
            }
        }
        return outputs;
    }

    private static ImageReference image(NodeConfiguration cfg) {
        return ImageReference.parse(cfg.requireString("image"));
    }

    /**
     * Writes a metadata-only audit record: who did what, to which registry, with what outcome — never a
     * credential, and never the secret's value or name's contents.
     */
    private void audit(NodeExecutionContext ctx, RegistryOperation operation, String provider,
                       NodeConfiguration cfg, String status, long millis) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("operation", operation.name());
            record.put("capability", operation.capability());
            record.put("riskLevel", operation.risk().name());
            record.put("provider", provider);
            record.put("workflowId", ctx.workflowId());
            record.put("workflowExecutionId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("repository", cfg.getString("repository", cfg.getString("image", null)));
            record.put("status", status);
            record.put("durationMillis", millis);
            record.put("timestamp", Instant.now().toString());
            context.dataStore().put("audit", ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt(),
                    record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write registry audit record: {}", ex.getMessage());
        }
    }
}
