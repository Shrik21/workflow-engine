package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub integration for OrchPilot workflows and AI Agents — repositories, branches, files, commits, pull
 * requests, issues, releases, Actions and search.
 *
 * <h2>Inside OrchPilot's plugin architecture</h2>
 *
 * Every GitHub concern lives here; the engine gains no GitHub code. The plugin reaches GitHub only through the
 * SDK's allow-listed {@link com.orchpilot.workflow.sdk.context.PluginHttpClient} (REST, so no GitHub SDK
 * dependency — which also lets it build offline) and reads the token through the
 * {@link com.orchpilot.workflow.sdk.context.SecretProvider} (never workflow config, never output, never the
 * model). Configuration is already variable-resolved, so {@code ${repo}} and {@code ${prNumber}} arrive
 * substituted through OrchPilot's own resolver. Each operation is its own node type ({@link GithubOperation}) so
 * the AI Agent sees distinct tools with per-operation risk — deletes and merges are {@code destructive}.
 *
 * <p>Thread-safe: the only field is the context, written once at initialise.
 */
public class GithubPlugin implements WorkflowNodePlugin {

    private static final String PLUGIN_ID = "orchpilot-github";
    private static final String PLUGIN_VERSION = "1.0.2";
    private static final String CATEGORY = "GitHub";

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "GitHub";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "GitHub integration: repositories, branches, files, commits, pull requests, issues, releases, "
                + "Actions and search.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("GitHub plugin initialised");
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("GitHub plugin destroyed");
        }
    }

    // ------------------------------------------------------------------ node catalogue

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>();
        for (GithubOperation operation : GithubOperation.values()) {
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .category(CATEGORY)
                    .icon("github")
                    .description(operation.description())
                    .configurationSchema(GithubSchemas.forOperation(operation))
                    .outputVariables("success", "result", "id", "number", "name", "fullName", "htmlUrl", "sha",
                            "state")
                    .idempotent(operation.risk() == GithubOperation.Risk.READ_ONLY)
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
        GithubOperation operation = GithubOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("GITHUB_UNKNOWN_OPERATION",
                    "Unknown GitHub node type: " + executionContext.nodeType());
        }
        NodeConfiguration cfg = executionContext.configuration();
        try {
            String token = context.secrets().require(cfg.requireString("credentialsSecret"));
            GithubClient client = new GithubClient(context.http(),
                    cfg.getString("githubApiUrl", GithubClient.DEFAULT_BASE_URL),
                    executionContext.timeoutMillis());

            NodeExecutionResult result = dispatch(operation, cfg, client, token);
            audit(executionContext, operation, cfg, result.isSuccess() ? "OK" : "FAILED");
            return result;
        } catch (GithubApiException ex) {
            context.logger().warn("GitHub {} failed: {} ({})", operation, ex.errorCode(), ex.getMessage());
            audit(executionContext, operation, cfg, "FAILED");
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure("GITHUB_MISCONFIGURED", ex.getMessage());
        }
    }

    private NodeExecutionResult dispatch(GithubOperation op, NodeConfiguration c, GithubClient gh, String token) {
        return switch (op) {
            // Repositories
            case GET_REPOSITORY -> object(gh.request("GET", repoPath(c), token, null));
            case LIST_REPOSITORIES -> array(gh.request("GET", listReposPath(c), token, null));
            case CREATE_REPOSITORY -> object(gh.request("POST", createRepoPath(c), token,
                    json(new Body().req(c, "name").str(c, "description").str(c, "homepage")
                            .bool(c, "private").bool(c, "has_issues").bool(c, "has_projects")
                            .bool(c, "has_wiki").bool(c, "auto_init"))));
            case UPDATE_REPOSITORY -> object(gh.request("PATCH", repoPath(c), token,
                    json(new Body().str(c, "name").str(c, "description").str(c, "homepage")
                            .str(c, "default_branch").str(c, "visibility").bool(c, "private")
                            .bool(c, "has_issues").bool(c, "has_projects").bool(c, "has_wiki")
                            .bool(c, "archived"))));
            case DELETE_REPOSITORY -> empty(gh.request("DELETE", repoPath(c), token, null), "DELETE_REPOSITORY");
            case FORK_REPOSITORY -> object(gh.request("POST", repoPath(c) + "/forks", token,
                    json(new Body().str(c, "organization"))));

            // Branches
            case LIST_BRANCHES -> array(gh.request("GET", repoPath(c) + "/branches", token, null));
            case GET_BRANCH -> object(gh.request("GET",
                    repoPath(c) + "/branches/" + enc(c.requireString("branch")), token, null));
            case CREATE_BRANCH -> createBranch(c, gh, token);
            case DELETE_BRANCH -> empty(gh.request("DELETE",
                    repoPath(c) + "/git/refs/heads/" + enc(c.requireString("branch")), token, null),
                    "DELETE_BRANCH");

            // Files
            case GET_FILE -> getFile(c, gh, token);
            case PUT_FILE -> object(gh.request("PUT", contentsPath(c), token,
                    json(new Body().req(c, "message")
                            .put("content", Base64.getEncoder().encodeToString(
                                    c.getString("content", "").getBytes(StandardCharsets.UTF_8)))
                            .str(c, "sha").str(c, "branch"))));
            case DELETE_FILE -> object(gh.request("DELETE", contentsPath(c), token,
                    json(new Body().req(c, "message").req(c, "sha").str(c, "branch"))));

            // Commits
            case LIST_COMMITS -> array(gh.request("GET", repoPath(c) + "/commits"
                    + query(c, "sha", "path", "author", "since", "until", "per_page", "page"), token, null));
            case GET_COMMIT -> object(gh.request("GET",
                    repoPath(c) + "/commits/" + enc(c.requireString("sha")), token, null));

            // Pull requests
            case CREATE_PULL_REQUEST -> object(gh.request("POST", repoPath(c) + "/pulls", token,
                    json(new Body().req(c, "title").req(c, "head").req(c, "base").str(c, "body")
                            .bool(c, "draft"))));
            case GET_PULL_REQUEST -> object(gh.request("GET",
                    repoPath(c) + "/pulls/" + number(c), token, null));
            case LIST_PULL_REQUESTS -> array(gh.request("GET", repoPath(c) + "/pulls"
                    + query(c, "state", "head", "base", "sort", "per_page", "page"), token, null));
            case UPDATE_PULL_REQUEST -> object(gh.request("PATCH", repoPath(c) + "/pulls/" + number(c), token,
                    json(new Body().str(c, "title").str(c, "body").str(c, "state").str(c, "base"))));
            case MERGE_PULL_REQUEST -> object(gh.request("PUT", repoPath(c) + "/pulls/" + number(c) + "/merge",
                    token, json(new Body().str(c, "commit_title").str(c, "commit_message")
                            .str(c, "merge_method"))));
            case REVIEW_PULL_REQUEST -> object(gh.request("POST",
                    repoPath(c) + "/pulls/" + number(c) + "/reviews", token,
                    json(new Body().req(c, "event").str(c, "body"))));
            case COMMENT_PULL_REQUEST -> object(gh.request("POST",
                    repoPath(c) + "/issues/" + number(c) + "/comments", token,
                    json(new Body().req(c, "body"))));

            // Issues
            case CREATE_ISSUE -> object(gh.request("POST", repoPath(c) + "/issues", token,
                    json(new Body().req(c, "title").str(c, "body").strList(c, "labels").strList(c, "assignees"))));
            case GET_ISSUE -> object(gh.request("GET", repoPath(c) + "/issues/" + number(c), token, null));
            case LIST_ISSUES -> array(gh.request("GET", repoPath(c) + "/issues"
                    + query(c, "state", "labels", "assignee", "creator", "sort", "per_page", "page"), token,
                    null));
            case UPDATE_ISSUE -> object(gh.request("PATCH", repoPath(c) + "/issues/" + number(c), token,
                    json(new Body().str(c, "title").str(c, "body").str(c, "state")
                            .strList(c, "labels").strList(c, "assignees"))));
            case COMMENT_ISSUE -> object(gh.request("POST", repoPath(c) + "/issues/" + number(c) + "/comments",
                    token, json(new Body().req(c, "body"))));

            // Releases
            case CREATE_RELEASE -> object(gh.request("POST", repoPath(c) + "/releases", token,
                    json(new Body().req(c, "tag_name").str(c, "target_commitish").str(c, "name").str(c, "body")
                            .bool(c, "draft").bool(c, "prerelease"))));
            case LIST_RELEASES -> array(gh.request("GET", repoPath(c) + "/releases"
                    + query(c, "per_page", "page"), token, null));

            // Actions
            case DISPATCH_WORKFLOW -> empty(gh.request("POST",
                    repoPath(c) + "/actions/workflows/" + enc(c.requireString("workflowId")) + "/dispatches",
                    token, json(new Body().req(c, "ref").map(c, "inputs"))), "DISPATCH_WORKFLOW");
            case LIST_WORKFLOW_RUNS -> object(gh.request("GET", repoPath(c) + "/actions/runs"
                    + query(c, "branch", "event", "status", "per_page", "page"), token, null));
            case GET_WORKFLOW_RUN -> object(gh.request("GET",
                    repoPath(c) + "/actions/runs/" + enc(c.requireString("runId")), token, null));
            case CANCEL_WORKFLOW_RUN -> empty(gh.request("POST",
                    repoPath(c) + "/actions/runs/" + enc(c.requireString("runId")) + "/cancel", token, ""),
                    "CANCEL_WORKFLOW_RUN");
            case RERUN_WORKFLOW_RUN -> empty(gh.request("POST",
                    repoPath(c) + "/actions/runs/" + enc(c.requireString("runId")) + "/rerun", token, ""),
                    "RERUN_WORKFLOW_RUN");

            // Search
            case SEARCH_REPOSITORIES -> object(gh.request("GET",
                    "/search/repositories" + query(c, "q", "sort", "order", "per_page", "page"), token, null));
            case SEARCH_CODE -> object(gh.request("GET",
                    "/search/code" + query(c, "q", "sort", "order", "per_page", "page"), token, null));
        };
    }

    // ------------------------------------------------------------------ special handlers

    private NodeExecutionResult createBranch(NodeConfiguration c, GithubClient gh, String token) {
        String source = c.getString("sourceBranch", "main");
        Map<String, Object> ref = obj(gh.request("GET",
                repoPath(c) + "/git/ref/heads/" + enc(source), token, null));
        String sha = str(dig(ref, "object", "sha"));
        if (sha == null) {
            throw new GithubApiException("GITHUB_NOT_FOUND",
                    "Source branch '" + source + "' was not found.", false);
        }
        String created = gh.request("POST", repoPath(c) + "/git/refs", token,
                json(new Body().put("ref", "refs/heads/" + c.requireString("newBranch")).put("sha", sha)));
        return object(created);
    }

    private NodeExecutionResult getFile(NodeConfiguration c, GithubClient gh, String token) {
        String body = gh.request("GET", contentsPath(c)
                + query(c, "ref"), token, null);
        Map<String, Object> file = obj(body);
        Map<String, Object> outputs = objectOutputs(file);
        Object encoded = file.get("content");
        if (encoded != null) {
            String decoded = new String(Base64.getMimeDecoder().decode(String.valueOf(encoded)),
                    StandardCharsets.UTF_8);
            outputs.put("content", decoded);
        }
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ path + query helpers

    private static String repoPath(NodeConfiguration c) {
        return "/repos/" + enc(c.requireString("owner")) + "/" + enc(c.requireString("repo"));
    }

    private static String contentsPath(NodeConfiguration c) {
        // The file path may contain slashes, which must stay as path separators, so encode each segment.
        String path = c.requireString("path");
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                encoded.append('/').append(enc(segment));
            }
        }
        return repoPath(c) + "/contents" + encoded;
    }

    private static String listReposPath(NodeConfiguration c) {
        String org = c.getString("org", null);
        String base = (org == null || org.isBlank()) ? "/user/repos" : "/orgs/" + enc(org) + "/repos";
        return base + query(c, "type", "sort", "direction", "per_page", "page");
    }

    private static String createRepoPath(NodeConfiguration c) {
        String org = c.getString("org", null);
        return (org == null || org.isBlank()) ? "/user/repos" : "/orgs/" + enc(org) + "/repos";
    }

    private static String number(NodeConfiguration c) {
        String value = c.findString("number").filter(s -> !s.isBlank())
                .or(() -> c.findString("pullNumber").filter(s -> !s.isBlank()))
                .or(() -> c.findString("issueNumber").filter(s -> !s.isBlank()))
                .orElseThrow(() -> new PluginConfigurationException(
                        "Required configuration 'number' (pull/issue number) is missing."));
        return enc(value);
    }

    /** Builds a {@code ?a=b&c=d} query string from the config values that are present. */
    private static String query(NodeConfiguration c, String... keys) {
        StringBuilder q = new StringBuilder();
        for (String key : keys) {
            String value = c.getString(key, null);
            if (value != null && !value.isBlank()) {
                q.append(q.length() == 0 ? '?' : '&').append(key).append('=').append(enc(value));
            }
        }
        return q.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ output helpers

    private NodeExecutionResult object(String body) {
        return NodeExecutionResult.success(objectOutputs(obj(body)));
    }

    private NodeExecutionResult array(String body) {
        List<Object> items = Json.parseArray(body);
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("count", items.size());
        outputs.put("items", items);
        return NodeExecutionResult.success(outputs);
    }

    /** For 201/202/204 responses that carry no body (dispatch, cancel, delete-ref). */
    private NodeExecutionResult empty(String body, String operation) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation);
        if (body != null && !body.isBlank()) {
            try {
                outputs.put("result", Json.parse(body));
            } catch (RuntimeException ignored) {
                // Some endpoints return an empty body; nothing to attach.
            }
        }
        return NodeExecutionResult.success(outputs);
    }

    private static Map<String, Object> objectOutputs(Map<String, Object> result) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("result", result);
        lift(result, outputs, "id", "id");
        lift(result, outputs, "number", "number");
        lift(result, outputs, "name", "name");
        lift(result, outputs, "full_name", "fullName");
        lift(result, outputs, "html_url", "htmlUrl");
        lift(result, outputs, "sha", "sha");
        lift(result, outputs, "state", "state");
        lift(result, outputs, "default_branch", "defaultBranch");
        lift(result, outputs, "merged", "merged");
        lift(result, outputs, "tag_name", "tagName");
        lift(result, outputs, "status", "status");
        lift(result, outputs, "conclusion", "conclusion");
        return outputs;
    }

    private static void lift(Map<String, Object> from, Map<String, Object> to, String key, String as) {
        if (from.get(key) != null) {
            to.put(as, from.get(key));
        }
    }

    private static Map<String, Object> obj(String body) {
        return (body == null || body.isBlank()) ? new LinkedHashMap<>() : Json.parseObject(body);
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String json(Body body) {
        return Json.write(body.map());
    }

    /** Metadata-only audit record; never a token. Best-effort, so it cannot fail the node. */
    private void audit(NodeExecutionContext ctx, GithubOperation op, NodeConfiguration c, String status) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("operation", op.name());
            record.put("riskLevel", op.risk().name());
            record.put("workflowId", ctx.workflowId());
            record.put("workflowExecutionId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("owner", c.getString("owner", null));
            record.put("repo", c.getString("repo", null));
            record.put("status", status);
            record.put("timestamp", java.time.Instant.now().toString());
            context.dataStore().put("audit", ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt(), record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write GitHub audit record: {}", ex.getMessage());
        }
    }

    /**
     * A small typed JSON body builder: only the fields that are present in configuration are added, coerced to the
     * right JSON type (a string, a boolean, a string array), so GitHub receives {@code "private": true} rather than
     * the string {@code "true"}.
     */
    static final class Body {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Body put(String key, Object value) {
            if (value != null) {
                map.put(key, value);
            }
            return this;
        }

        Body req(NodeConfiguration c, String key) {
            map.put(key, c.requireString(key));
            return this;
        }

        Body str(NodeConfiguration c, String key) {
            c.findString(key).filter(s -> !s.isBlank()).ifPresent(v -> map.put(key, v));
            return this;
        }

        Body bool(NodeConfiguration c, String key) {
            if (c.has(key)) {
                map.put(key, c.getBoolean(key, false));
            }
            return this;
        }

        Body map(NodeConfiguration c, String key) {
            Map<String, Object> nested = c.getMap(key);
            if (!nested.isEmpty()) {
                map.put(key, nested);
            }
            return this;
        }

        /** A list value, or a comma/space-separated string, becomes a JSON string array. */
        Body strList(NodeConfiguration c, String key) {
            Object raw = c.find(key).orElse(null);
            List<String> values = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        values.add(String.valueOf(item).trim());
                    }
                }
            } else if (raw != null && !String.valueOf(raw).isBlank()) {
                for (String part : String.valueOf(raw).split("[,\\s]+")) {
                    if (!part.isBlank()) {
                        values.add(part.trim());
                    }
                }
            }
            if (!values.isEmpty()) {
                map.put(key, values);
            }
            return this;
        }

        Map<String, Object> map() {
            return map;
        }
    }
}
