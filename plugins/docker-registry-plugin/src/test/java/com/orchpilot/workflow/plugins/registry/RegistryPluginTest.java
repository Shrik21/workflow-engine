package com.orchpilot.workflow.plugins.registry;

import com.orchpilot.workflow.plugins.registry.model.RegistryOperation;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin surface: the node catalogue and its risk flags, dispatch through to a provider, and the property
 * that matters most — that a credential never reaches an output, a message, or anything the AI Agent sees.
 */
class RegistryPluginTest {

    private static final String TOKEN = "dckr_pat_supersecret";

    private RegistryPlugin plugin;
    private FakeHttpClient http;

    @BeforeEach
    void setUp() {
        http = new FakeHttpClient();
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn("robot:" + TOKEN);

        PluginContext context = mock(PluginContext.class);
        lenient().when(context.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(context.secrets()).thenReturn(secrets);
        lenient().when(context.http()).thenReturn(http);
        lenient().when(context.dataStore()).thenReturn(mock(PluginDataStore.class));

        plugin = new RegistryPlugin();
        plugin.initialize(context);
    }

    private NodeExecutionContext ctx(String nodeType, Map<String, Object> config) {
        NodeExecutionContext c = mock(NodeExecutionContext.class);
        lenient().when(c.nodeType()).thenReturn(nodeType);
        lenient().when(c.configuration()).thenReturn(configuration(config));
        lenient().when(c.executionId()).thenReturn("exec-1");
        lenient().when(c.workflowId()).thenReturn("wf-1");
        lenient().when(c.nodeId()).thenReturn("node-1");
        lenient().when(c.attempt()).thenReturn(1);
        lenient().when(c.timeoutMillis()).thenReturn(30_000L);
        lenient().when(c.currentUser()).thenReturn(Optional.empty());
        return c;
    }

    private static NodeConfiguration configuration(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        return new NodeConfiguration() {
            @Override
            public Optional<Object> find(String key) {
                return Optional.ofNullable(copy.get(key));
            }

            @Override
            public Map<String, Object> asMap() {
                return Collections.unmodifiableMap(copy);
            }
        };
    }

    private Map<String, Object> generic(String... extra) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("provider", "GENERIC");
        cfg.put("credentialsSecret", "registry.harbor");
        cfg.put("registryUrl", "harbor.example.com");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            cfg.put(extra[i], extra[i + 1]);
        }
        return cfg;
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void everyOperationBecomesAnAiEnabledNode() {
        List<NodeDefinition> definitions = plugin.getNodeDefinitions();

        assertThat(definitions).hasSize(RegistryOperation.values().length);
        assertThat(definitions).allMatch(NodeDefinition::supportsAI);
        assertThat(definitions).allMatch(d -> d.category().equals("Container Registry"));
    }

    @Test
    void onlyHighRiskOperationsAreDestructive() {
        // The whole reason for one node per operation: the agent can list and read freely, but a delete is
        // gated. If this ever collapses to "all or nothing", that guarantee is gone.
        Set<RegistryOperation> expected =
                EnumSet.of(RegistryOperation.DELETE_IMAGE, RegistryOperation.DELETE_REPOSITORY);

        for (RegistryOperation operation : RegistryOperation.values()) {
            NodeDefinition definition = definition(operation.nodeType());
            assertThat(definition.destructive())
                    .as("%s destructive", operation)
                    .isEqualTo(expected.contains(operation));
            assertThat(operation.destructive())
                    .isEqualTo(operation.risk() == RegistryOperation.RiskLevel.HIGH);
        }
    }

    @Test
    void readOnlyOperationsAreIdempotentAndWritesAreNot() {
        assertThat(definition("REGISTRY_LIST_TAGS").idempotent()).isTrue();
        assertThat(definition("REGISTRY_GET_DIGEST").idempotent()).isTrue();
        assertThat(definition("REGISTRY_DELETE_IMAGE").idempotent()).isFalse();
        assertThat(definition("REGISTRY_RETAG").idempotent()).isFalse();
    }

    @Test
    void everyOperationDeclaresANamespacedCapabilityForAiDiscovery() {
        for (RegistryOperation operation : RegistryOperation.values()) {
            assertThat(operation.capability()).startsWith("container.registry.");
            // The node's description carries the capability so it reaches the agent's tool description too.
            assertThat(definition(operation.nodeType()).description()).contains(operation.capability());
        }
        assertThat(RegistryOperation.LIST_IMAGES.capability()).isEqualTo("container.registry.listImages");
        assertThat(RegistryOperation.DELETE_IMAGE.capability()).isEqualTo("container.registry.deleteImage");
    }

    @Test
    void aiToolNamesDeriveFromNodeTypes() {
        assertThat(toolName("REGISTRY_DELETE_IMAGE")).isEqualTo("registry_delete_image");
        assertThat(toolName("REGISTRY_LIST_REPOSITORIES")).isEqualTo("registry_list_repositories");
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    void listTagsReachesTheRegistryAndPublishesStructuredOutput() {
        http.on("GET https://harbor.example.com/v2/team/api/tags/list", 200,
                "{\"name\":\"team/api\",\"tags\":[\"1.0\",\"1.1\"]}");

        NodeExecutionResult result = plugin.execute(
                ctx("REGISTRY_LIST_TAGS", generic("repository", "team/api")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("success", true);
        assertThat(result.outputs()).containsEntry("provider", "GENERIC");
        assertThat(result.outputs()).containsEntry("operation", "LIST_TAGS");
        assertThat(result.outputs()).containsEntry("count", 2);
    }

    @Test
    void aRegistryFailureBecomesANormalisedRetryableFlag() {
        http.on("GET https://harbor.example.com/v2/team/api/tags/list", 429, "{\"errors\":[]}");

        NodeExecutionResult result = plugin.execute(
                ctx("REGISTRY_LIST_TAGS", generic("repository", "team/api")));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.errorCode()).isEqualTo("RATE_LIMITED");
        // A rate limit clears on its own, so the engine should try again; a bad credential would not.
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void anAuthenticationFailureIsNotRetried() {
        http.on("GET https://harbor.example.com/v2/team/api/tags/list", 401, "{\"errors\":[]}");

        NodeExecutionResult result = plugin.execute(
                ctx("REGISTRY_LIST_TAGS", generic("repository", "team/api")));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.errorCode()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void anUnparseableImageReferenceFailsClearlyRatherThanReachingTheRegistry() {
        NodeExecutionResult result = plugin.execute(ctx("REGISTRY_GET_DIGEST", generic("image", "")));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.errorCode()).isIn("INVALID_IMAGE", "REGISTRY_MISCONFIGURED");
        assertThat(http.requests).isEmpty();
    }

    // ------------------------------------------------------------------ credential safety

    @Test
    void theCredentialNeverAppearsInOutputsOrMessages() {
        http.on("GET https://harbor.example.com/v2/team/api/tags/list", 200,
                "{\"name\":\"team/api\",\"tags\":[\"1.0\"]}");

        NodeExecutionResult result = plugin.execute(
                ctx("REGISTRY_LIST_TAGS", generic("repository", "team/api")));

        assertThat(String.valueOf(result.outputs())).doesNotContain(TOKEN);
        // It is of course sent to the registry — that is the point — but only in the Authorization header.
        assertThat(http.lastMatching("/tags/list").headers().get("Authorization")).isNotNull();
    }

    @Test
    void aFailureMessageNeverCarriesTheCredentialEither() {
        http.on("GET https://harbor.example.com/v2/team/api/tags/list", 403, "{\"errors\":[{\"code\":\"DENIED\"}]}");

        NodeExecutionResult result = plugin.execute(
                ctx("REGISTRY_LIST_TAGS", generic("repository", "team/api")));

        assertThat(result.errorMessage()).doesNotContain(TOKEN);
        assertThat(result.errorMessage()).doesNotContain("robot:");
    }

    // ------------------------------------------------------------------ helpers

    private NodeDefinition definition(String nodeType) {
        return plugin.getNodeDefinitions().stream()
                .filter(d -> d.nodeType().equals(nodeType))
                .findFirst()
                .orElseThrow();
    }

    private static String toolName(String nodeType) {
        return nodeType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }
}
