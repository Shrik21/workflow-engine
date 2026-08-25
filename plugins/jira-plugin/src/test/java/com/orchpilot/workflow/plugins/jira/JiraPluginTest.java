package com.orchpilot.workflow.plugins.jira;

import com.orchpilot.workflow.plugins.jira.model.JiraOperation;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The plugin surface: the node catalogue and its risk flags, dispatch against a scripted Jira, and the two
 * behaviours most likely to be got wrong — resolving a transition <em>name</em> to an id, and never letting the
 * API token reach an output or a message.
 */
class JiraPluginTest {

    private static final String TOKEN = "ATATT-supersecret-token";
    private static final String API = "https://company.atlassian.net/rest/api/3";

    private JiraPlugin plugin;
    private Fake http;

    /** Minimal scriptable HTTP client. */
    private static final class Fake implements PluginHttpClient {
        private final List<Function<HttpRequestSpec, HttpResponseView>> rules = new ArrayList<>();
        private final List<Function<HttpRequestSpec, Boolean>> matchers = new ArrayList<>();
        final List<HttpRequestSpec> requests = new ArrayList<>();

        Fake on(String methodAndUri, int status, String body) {
            matchers.add(req -> (req.method() + " " + req.uri()).contains(methodAndUri));
            rules.add(req -> new HttpResponseView(status, Map.of(), body, 1));
            return this;
        }

        @Override
        public HttpResponseView execute(HttpRequestSpec request) {
            requests.add(request);
            for (int i = 0; i < matchers.size(); i++) {
                if (matchers.get(i).apply(request)) {
                    return rules.get(i).apply(request);
                }
            }
            throw new AssertionError("No rule matched: " + request.method() + " " + request.uri());
        }

        HttpRequestSpec last(String method, String uriPart) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                HttpRequestSpec r = requests.get(i);
                if (r.method().equals(method) && r.uri().contains(uriPart)) {
                    return r;
                }
            }
            throw new AssertionError("No recorded " + method + " for " + uriPart);
        }
    }

    @BeforeEach
    void setUp() {
        http = new Fake();
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn("bot@company.com:" + TOKEN);

        PluginContext context = mock(PluginContext.class);
        lenient().when(context.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(context.secrets()).thenReturn(secrets);
        lenient().when(context.http()).thenReturn(http);
        lenient().when(context.dataStore()).thenReturn(mock(PluginDataStore.class));

        plugin = new JiraPlugin();
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

    private Map<String, Object> base(String... extra) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("baseUrl", "https://company.atlassian.net");
        cfg.put("deployment", "CLOUD");
        cfg.put("credentialsSecret", "jira.prod");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            cfg.put(extra[i], extra[i + 1]);
        }
        return cfg;
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    void everyOperationBecomesAnAiEnabledNodeCarryingItsCapability() {
        List<NodeDefinition> definitions = plugin.getNodeDefinitions();

        assertThat(definitions).hasSize(JiraOperation.values().length);
        assertThat(definitions).allMatch(NodeDefinition::supportsAI);
        for (JiraOperation operation : JiraOperation.values()) {
            assertThat(operation.capability()).startsWith("jira.");
            assertThat(definition(operation.nodeType()).description()).contains(operation.capability());
        }
    }

    @Test
    void onlyIrreversibleOperationsAreDestructive() {
        // The reason for one node per operation: a JQL search must stay ungated while a delete does not.
        Set<JiraOperation> expected = EnumSet.of(JiraOperation.ISSUE_DELETE, JiraOperation.COMMENT_DELETE,
                JiraOperation.COMPONENT_DELETE);
        for (JiraOperation operation : JiraOperation.values()) {
            assertThat(definition(operation.nodeType()).destructive())
                    .as("%s destructive", operation)
                    .isEqualTo(expected.contains(operation));
        }
        assertThat(definition("JIRA_SEARCH_ISSUES").destructive()).isFalse();
        assertThat(definition("JIRA_SEARCH_ISSUES").idempotent()).isTrue();
        assertThat(definition("JIRA_CREATE_ISSUE").idempotent()).isFalse();
    }

    @Test
    void aiToolNamesDeriveFromNodeTypes() {
        assertThat(toolName("JIRA_SEARCH_ISSUES")).isEqualTo("jira_search_issues");
        assertThat(toolName("JIRA_TRANSITION_ISSUE")).isEqualTo("jira_transition_issue");
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    void createIssuePublishesTheKeyAndUrlAndSendsAdfDescription() {
        http.on("POST " + API + "/issue", 201, "{\"id\":\"10001\",\"key\":\"ENG-123\"}");

        NodeExecutionResult result = plugin.execute(ctx("JIRA_CREATE_ISSUE",
                base("projectKey", "ENG", "issueType", "Bug", "summary", "Login fails",
                        "description", "HTTP 500 on submit", "priority", "High", "labels", "auth,urgent")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("issueKey", "ENG-123");
        assertThat(result.outputs()).containsEntry("issueId", "10001");
        assertThat(result.outputs())
                .containsEntry("issueUrl", "https://company.atlassian.net/browse/ENG-123");

        String sent = http.last("POST", "/issue").body();
        assertThat(sent).contains("\"key\":\"ENG\"").contains("Login fails");
        // Cloud requires ADF, so the description must be a document, not a bare string.
        assertThat(sent).contains("\"type\":\"doc\"").contains("HTTP 500 on submit");
        assertThat(sent).contains("\"labels\":[\"auth\",\"urgent\"]");
    }

    @Test
    void searchFlattensIssuesIntoSomethingAWorkflowCanBranchOn() {
        http.on("POST " + API + "/search", 200,
                "{\"total\":2,\"issues\":["
                        + "{\"id\":\"1\",\"key\":\"ENG-1\",\"fields\":{\"summary\":\"A\","
                        + "\"status\":{\"name\":\"Open\"},\"priority\":{\"name\":\"High\"},"
                        + "\"assignee\":{\"displayName\":\"Vivek\"}}},"
                        + "{\"id\":\"2\",\"key\":\"ENG-2\",\"fields\":{\"summary\":\"B\","
                        + "\"status\":{\"name\":\"Done\"}}}]}");

        NodeExecutionResult result = plugin.execute(ctx("JIRA_SEARCH_ISSUES",
                base("jql", "project = ENG AND priority = High")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("total", 2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) result.outputs().get("issues");
        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).containsEntry("issueKey", "ENG-1");
        assertThat(issues.get(0)).containsEntry("status", "Open");
        assertThat(issues.get(0)).containsEntry("assignee", "Vivek");
        assertThat(issues.get(0)).containsEntry("issueUrl", "https://company.atlassian.net/browse/ENG-1");

        assertThat(http.last("POST", "/search").body()).contains("project = ENG AND priority = High");
    }

    @Test
    void transitionResolvesAHumanNameToTheIdJiraActuallyNeeds() {
        // Transition ids are per-workflow and unguessable; an author (and the agent) says "In Progress".
        http.on("GET " + API + "/issue/ENG-5/transitions", 200,
                "{\"transitions\":[{\"id\":\"11\",\"name\":\"To Do\",\"to\":{\"name\":\"To Do\"}},"
                        + "{\"id\":\"31\",\"name\":\"In Progress\",\"to\":{\"name\":\"In Progress\"}}]}")
                .on("POST " + API + "/issue/ENG-5/transitions", 204, "");

        NodeExecutionResult result = plugin.execute(ctx("JIRA_TRANSITION_ISSUE",
                base("issueKey", "ENG-5", "transition", "In Progress")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("transitionId", "31");
        assertThat(http.last("POST", "/transitions").body()).contains("\"id\":\"31\"");
    }

    @Test
    void anImpossibleTransitionSaysWhatWasActuallyAvailable() {
        http.on("GET " + API + "/issue/ENG-5/transitions", 200,
                "{\"transitions\":[{\"id\":\"11\",\"name\":\"To Do\",\"to\":{\"name\":\"To Do\"}}]}");

        NodeExecutionResult result = plugin.execute(ctx("JIRA_TRANSITION_ISSUE",
                base("issueKey", "ENG-5", "transition", "Done")));

        assertThat(result.isFailed()).isTrue();
        assertThat(result.errorCode()).isEqualTo("JIRA_TRANSITION_NOT_AVAILABLE");
        // The message must name what the issue could do, or the author is left guessing.
        assertThat(result.errorMessage()).contains("To Do");
    }

    @Test
    void assigneeShapeFollowsTheDeployment() {
        http.on("PUT " + API + "/issue/ENG-9/assignee", 204, "");
        plugin.execute(ctx("JIRA_ASSIGN_ISSUE", base("issueKey", "ENG-9", "assignee", "acc-123")));
        // Cloud identifies users by accountId; Server by name. Sending the wrong one is a silent 400.
        assertThat(http.last("PUT", "/assignee").body()).contains("accountId");

        Fake serverHttp = new Fake();
        serverHttp.on("PUT https://jira.internal/rest/api/2/issue/ENG-9/assignee", 204, "");
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn("pat-token");
        PluginContext serverContext = mock(PluginContext.class);
        lenient().when(serverContext.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(serverContext.secrets()).thenReturn(secrets);
        lenient().when(serverContext.http()).thenReturn(serverHttp);
        lenient().when(serverContext.dataStore()).thenReturn(mock(PluginDataStore.class));
        JiraPlugin serverPlugin = new JiraPlugin();
        serverPlugin.initialize(serverContext);

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("baseUrl", "https://jira.internal");
        cfg.put("deployment", "SERVER");
        cfg.put("credentialsSecret", "jira.dc");
        cfg.put("issueKey", "ENG-9");
        cfg.put("assignee", "vivek");
        serverPlugin.execute(ctx("JIRA_ASSIGN_ISSUE", cfg));
        assertThat(serverHttp.last("PUT", "/assignee").body()).contains("\"name\"");
    }

    @Test
    void aFailureIsNormalisedAndOnlyTransientOnesRetry() {
        http.on("POST " + API + "/search", 429, "{\"errorMessages\":[\"Rate limit\"]}");
        NodeExecutionResult limited = plugin.execute(ctx("JIRA_SEARCH_ISSUES", base("jql", "project = ENG")));
        assertThat(limited.errorCode()).isEqualTo("JIRA_RATE_LIMITED");
        assertThat(limited.retryable()).isTrue();

        Fake denied = new Fake();
        denied.on("POST " + API + "/issue", 403, "{\"errorMessages\":[\"No create permission\"]}");
        SecretProvider secrets = mock(SecretProvider.class);
        when(secrets.require(anyString())).thenReturn("bot@company.com:" + TOKEN);
        PluginContext c = mock(PluginContext.class);
        lenient().when(c.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(c.secrets()).thenReturn(secrets);
        lenient().when(c.http()).thenReturn(denied);
        lenient().when(c.dataStore()).thenReturn(mock(PluginDataStore.class));
        JiraPlugin p = new JiraPlugin();
        p.initialize(c);

        NodeExecutionResult forbidden = p.execute(ctx("JIRA_CREATE_ISSUE",
                base("projectKey", "ENG", "summary", "x")));
        assertThat(forbidden.errorCode()).isEqualTo("JIRA_PERMISSION_DENIED");
        assertThat(forbidden.retryable()).isFalse();
    }

    // ------------------------------------------------------------------ credential safety

    @Test
    void theApiTokenNeverAppearsInOutputsOrErrorMessages() {
        http.on("POST " + API + "/issue", 400,
                "{\"errors\":{\"summary\":\"Summary is required\"}}");

        NodeExecutionResult result = plugin.execute(ctx("JIRA_CREATE_ISSUE",
                base("projectKey", "ENG", "summary", "x")));

        assertThat(result.errorMessage()).doesNotContain(TOKEN).doesNotContain("bot@company.com");
        // The useful field-level detail still comes through.
        assertThat(result.errorMessage()).contains("Summary is required");
        assertThat(String.valueOf(result.outputs())).doesNotContain(TOKEN);
        // It is of course sent to Jira — but only in the Authorization header.
        assertThat(http.last("POST", "/issue").headers().get("Authorization")).startsWith("Basic ");
    }

    // ------------------------------------------------------------------ helpers

    private NodeDefinition definition(String nodeType) {
        return plugin.getNodeDefinitions().stream()
                .filter(d -> d.nodeType().equals(nodeType))
                .findFirst().orElseThrow();
    }

    private static String toolName(String nodeType) {
        return nodeType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }
}
