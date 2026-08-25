package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin end to end, driven through a mocked {@link PluginContext} and a scripted HTTP client — no GitHub
 * account, no network. Covers the catalogue and its risk flags, a read, a body-building write, the two-step
 * create-branch, the base64 file decode, and a permission error mapped to a clean failure with no token leak.
 */
class GithubPluginTest {

    private static final String API = "https://api.github.com";

    private GithubPlugin plugin;
    private FakeHttpClient http;

    @BeforeEach
    void setUp() {
        http = new FakeHttpClient();
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn("ghp_secrettoken");

        PluginContext context = mock(PluginContext.class);
        lenient().when(context.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(context.secrets()).thenReturn(secrets);
        lenient().when(context.http()).thenReturn(http);
        lenient().when(context.dataStore()).thenReturn(mock(PluginDataStore.class));

        plugin = new GithubPlugin();
        plugin.initialize(context);
    }

    private NodeExecutionContext ctx(String nodeType, Map<String, Object> config) {
        NodeExecutionContext c = mock(NodeExecutionContext.class);
        lenient().when(c.nodeType()).thenReturn(nodeType);
        lenient().when(c.configuration()).thenReturn(new MapConfiguration(config));
        lenient().when(c.executionId()).thenReturn("exec-1");
        lenient().when(c.workflowId()).thenReturn("wf-1");
        lenient().when(c.nodeId()).thenReturn("node-1");
        lenient().when(c.attempt()).thenReturn(1);
        lenient().when(c.timeoutMillis()).thenReturn(30_000L);
        lenient().when(c.currentUser()).thenReturn(Optional.empty());
        return c;
    }

    private Map<String, Object> repo(String... extra) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("credentialsSecret", "github.test");
        cfg.put("owner", "octo");
        cfg.put("repo", "demo");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            cfg.put(extra[i], extra[i + 1]);
        }
        return cfg;
    }

    @Test
    void catalogueHas36NodesWithCorrectRiskFlags() {
        var defs = plugin.getNodeDefinitions();
        assertThat(defs).hasSize(36);
        assertThat(defs).allMatch(NodeDefinition::supportsAI);
        assertThat(def(defs, "GITHUB_DELETE_REPOSITORY").destructive()).isTrue();
        assertThat(def(defs, "GITHUB_MERGE_PULL_REQUEST").destructive()).isTrue();
        assertThat(def(defs, "GITHUB_GET_REPOSITORY").destructive()).isFalse();
        assertThat(def(defs, "GITHUB_GET_REPOSITORY").idempotent()).isTrue();
    }

    @Test
    void getRepositoryLiftsCommonFields() {
        http.on("GET " + API + "/repos/octo/demo", 200,
                "{\"id\":5,\"full_name\":\"octo/demo\",\"default_branch\":\"main\",\"private\":true}");

        NodeExecutionResult result = plugin.execute(ctx("GITHUB_GET_REPOSITORY", repo()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("fullName", "octo/demo");
        assertThat(result.outputs()).containsEntry("defaultBranch", "main");
        assertThat(result.outputs()).containsKey("result");
    }

    @Test
    void createIssueBuildsATypedBody() {
        http.on("POST " + API + "/repos/octo/demo/issues", 201,
                "{\"number\":42,\"html_url\":\"https://github.com/octo/demo/issues/42\"}");

        Map<String, Object> cfg = repo("title", "My bug", "body", "It broke", "labels", "bug,urgent");
        NodeExecutionResult result = plugin.execute(ctx("GITHUB_CREATE_ISSUE", cfg));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("number", 42L);
        String sent = http.lastRequestMatching("/issues").body();
        assertThat(sent).contains("\"title\":\"My bug\"");
        assertThat(sent).contains("\"labels\":[\"bug\",\"urgent\"]");
    }

    @Test
    void createBranchResolvesSourceShaThenCreatesRef() {
        http.on("GET " + API + "/repos/octo/demo/git/ref/heads/main", 200,
                        "{\"object\":{\"sha\":\"abc123\"}}")
                .on("POST " + API + "/repos/octo/demo/git/refs", 201,
                        "{\"ref\":\"refs/heads/feature\",\"object\":{\"sha\":\"abc123\"}}");

        NodeExecutionResult result = plugin.execute(ctx("GITHUB_CREATE_BRANCH",
                repo("sourceBranch", "main", "newBranch", "feature")));

        assertThat(result.isSuccess()).isTrue();
        String sent = http.lastRequestMatching("/git/refs").body();
        assertThat(sent).contains("refs/heads/feature");
        assertThat(sent).contains("abc123");
    }

    @Test
    void getFileDecodesBase64Content() {
        // base64("hello") = aGVsbG8=
        http.on("GET " + API + "/repos/octo/demo/contents/README.md", 200,
                "{\"content\":\"aGVsbG8=\",\"sha\":\"f1\"}");

        NodeExecutionResult result = plugin.execute(ctx("GITHUB_GET_FILE",
                repo("path", "README.md")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("content", "hello");
        assertThat(result.outputs()).containsEntry("sha", "f1");
    }

    @Test
    void permissionErrorBecomesACleanFailure() {
        http.on("GET " + API + "/repos/octo/demo", 404, "{\"message\":\"Not Found\"}");

        NodeExecutionResult result = plugin.execute(ctx("GITHUB_GET_REPOSITORY", repo()));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.errorCode()).isEqualTo("GITHUB_NOT_FOUND");
        assertThat(result.errorMessage()).doesNotContain("ghp_");
    }

    private static NodeDefinition def(java.util.List<NodeDefinition> defs, String nodeType) {
        return defs.stream().filter(d -> d.nodeType().equals(nodeType)).findFirst().orElseThrow();
    }
}
