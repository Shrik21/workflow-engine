package com.orchpilot.workflow.plugins.jira;

import com.orchpilot.workflow.plugins.jira.model.JiraOperation;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jira as an execution capability for OrchPilot workflows and the existing AI Agent.
 *
 * <p>Each {@link JiraOperation} is its own node type, so the agent sees a distinct tool with a distinct risk
 * level — a JQL search runs freely while deleting an issue is gated by the platform's existing approval policy.
 * Credentials come from the secret store by name and never enter node configuration, output, logs, or the
 * agent's context. Cloud and Server differences are absorbed by {@link JiraClient}.
 */
public class JiraPlugin implements WorkflowNodePlugin {

    private static final String PLUGIN_ID = "orchpilot-jira";
    private static final String PLUGIN_VERSION = "1.0.1";
    private static final String CATEGORY = "Jira";

    /** A useful default field set: enough to act on an issue without asking for everything Jira stores. */
    private static final String DEFAULT_FIELDS =
            "summary,status,priority,assignee,reporter,issuetype,created,updated,labels,duedate";

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Jira";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Create, search, update and transition Jira issues, and manage comments, worklogs, sprints, "
                + "boards, versions and components.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("Jira plugin initialised with {} operations", JiraOperation.values().length);
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("Jira plugin destroyed");
        }
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>();
        for (JiraOperation operation : JiraOperation.values()) {
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .description(operation.description() + "  [capability: " + operation.capability() + "]")
                    .category(CATEGORY)
                    .icon("jira")
                    .configurationSchema(JiraSchemas.forOperation(operation))
                    .outputVariables("success", "operation", "issueKey", "issueId", "issueUrl", "total",
                            "issues", "result")
                    .idempotent(operation.risk() == JiraOperation.RiskLevel.READ_ONLY)
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
        JiraOperation operation = JiraOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("JIRA_UNKNOWN_OPERATION",
                    "Unknown Jira node type: " + executionContext.nodeType());
        }
        NodeConfiguration cfg = executionContext.configuration();
        Instant started = Instant.now();

        try {
            JiraClient jira = new JiraClient(context.http(),
                    cfg.requireString("baseUrl"),
                    context.secrets().require(cfg.requireString("credentialsSecret")),
                    JiraClient.Deployment.parse(cfg.getString("deployment", "CLOUD")),
                    executionContext.timeoutMillis());

            Map<String, Object> outputs = dispatch(operation, jira, cfg);
            outputs.put("success", true);
            outputs.put("operation", operation.name());

            long millis = java.time.Duration.between(started, Instant.now()).toMillis();
            audit(executionContext, operation, cfg, "SUCCESS", millis);
            return NodeExecutionResult.success(outputs);

        } catch (JiraException ex) {
            long millis = java.time.Duration.between(started, Instant.now()).toMillis();
            context.logger().warn("Jira {} failed: {} ({})", operation, ex.errorCode(), ex.getMessage());
            audit(executionContext, operation, cfg, "FAILED", millis);
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure("JIRA_MISCONFIGURED", ex.getMessage());
        }
    }

    private Map<String, Object> dispatch(JiraOperation operation, JiraClient jira, NodeConfiguration cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (operation) {

            // ---------------------------------------------------------- issues
            case ISSUE_CREATE -> {
                Map<String, Object> created = jira.post(jira.api() + "/issue",
                        Map.of("fields", issueFields(jira, cfg, true)), "issue creation");
                issueIdentity(out, jira, created);
                out.put("result", created);
            }

            case ISSUE_GET -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> issue = jira.get(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "?fields=" + DEFAULT_FIELDS,
                        "issue " + key);
                out.putAll(summarise(jira, issue));
                out.put("result", issue);
            }

            case ISSUE_UPDATE -> {
                String key = cfg.requireString("issueKey");
                jira.put(jira.api() + "/issue/" + JiraClient.enc(key),
                        Map.of("fields", issueFields(jira, cfg, false)), "issue " + key);
                out.put("issueKey", key);
                out.put("issueUrl", browseUrl(jira, key));
            }

            case ISSUE_DELETE -> {
                String key = cfg.requireString("issueKey");
                jira.delete(jira.api() + "/issue/" + JiraClient.enc(key)
                        + "?deleteSubtasks=" + cfg.getBoolean("deleteSubtasks", false), "issue " + key);
                out.put("issueKey", key);
            }

            case ISSUE_SEARCH -> {
                String fields = cfg.getString("fields", DEFAULT_FIELDS);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jql", cfg.requireString("jql"));
                body.put("startAt", cfg.getInt("startAt", 0));
                body.put("maxResults", cfg.getInt("maxResults", 50));
                body.put("fields", Arrays.asList(fields.split("\\s*,\\s*")));

                Map<String, Object> page = jira.post(jira.api() + "/search", body, "JQL search");
                List<Map<String, Object>> issues = new ArrayList<>();
                if (page.get("issues") instanceof List<?> raw) {
                    for (Object item : raw) {
                        if (item instanceof Map<?, ?> issue) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) issue;
                            issues.add(summarise(jira, typed));
                        }
                    }
                }
                out.put("issues", issues);
                out.put("total", page.get("total"));
                out.put("count", issues.size());
            }

            case ISSUE_ASSIGN -> {
                String key = cfg.requireString("issueKey");
                String assignee = cfg.getString("assignee", null);
                // Jira distinguishes "unassign" (null) from "back to default" (-1); blank means unassign here.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put(jira.deployment() == JiraClient.Deployment.CLOUD ? "accountId" : "name",
                        assignee == null || assignee.isBlank() ? null : assignee);
                jira.put(jira.api() + "/issue/" + JiraClient.enc(key) + "/assignee", body, "issue " + key);
                out.put("issueKey", key);
                out.put("assignee", assignee);
            }

            case ISSUE_TRANSITION -> out.putAll(transition(jira, cfg));

            case ISSUE_CLONE -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> source = jira.get(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "?fields=" + DEFAULT_FIELDS
                                + ",description,components",
                        "issue " + key);
                @SuppressWarnings("unchecked")
                Map<String, Object> sourceFields = source.get("fields") instanceof Map
                        ? (Map<String, Object>) source.get("fields") : Map.of();

                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("project", Map.of("key", cfg.getString("projectKey", projectOf(sourceFields, key))));
                fields.put("summary", cfg.getString("summary",
                        "CLONE - " + String.valueOf(sourceFields.get("summary"))));
                Object type = sourceFields.get("issuetype");
                if (type instanceof Map<?, ?> typeMap && typeMap.get("name") != null) {
                    fields.put("issuetype", Map.of("name", typeMap.get("name")));
                }
                Object description = sourceFields.get("description");
                if (description != null) {
                    fields.put("description", jira.richText(JiraClient.plainText(description)));
                }
                Map<String, Object> created = jira.post(jira.api() + "/issue", Map.of("fields", fields),
                        "issue clone");
                issueIdentity(out, jira, created);
                out.put("clonedFrom", key);
            }

            case ISSUE_CHANGELOG -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> changelog = jira.get(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "/changelog", "changelog of " + key);
                out.put("issueKey", key);
                out.put("result", changelog);
            }

            case TRANSITION_LIST -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> body = jira.get(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "/transitions", "transitions of " + key);
                List<Map<String, Object>> transitions = new ArrayList<>();
                if (body.get("transitions") instanceof List<?> raw) {
                    for (Object item : raw) {
                        if (item instanceof Map<?, ?> t) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", t.get("id"));
                            entry.put("name", t.get("name"));
                            if (t.get("to") instanceof Map<?, ?> to) {
                                entry.put("toStatus", to.get("name"));
                            }
                            transitions.add(entry);
                        }
                    }
                }
                out.put("issueKey", key);
                out.put("transitions", transitions);
                out.put("count", transitions.size());
            }

            // ---------------------------------------------------------- comments
            case COMMENT_ADD -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> comment = jira.post(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "/comment",
                        Map.of("body", jira.richText(cfg.requireString("comment"))), "comment on " + key);
                out.put("issueKey", key);
                out.put("commentId", comment.get("id"));
            }

            case COMMENT_LIST -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> body = jira.get(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "/comment", "comments on " + key);
                List<Map<String, Object>> comments = new ArrayList<>();
                if (body.get("comments") instanceof List<?> raw) {
                    for (Object item : raw) {
                        if (item instanceof Map<?, ?> c) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", c.get("id"));
                            entry.put("body", JiraClient.plainText(c.get("body")));
                            entry.put("created", c.get("created"));
                            if (c.get("author") instanceof Map<?, ?> author) {
                                entry.put("author", author.get("displayName"));
                            }
                            comments.add(entry);
                        }
                    }
                }
                out.put("issueKey", key);
                out.put("comments", comments);
                out.put("count", comments.size());
            }

            case COMMENT_UPDATE -> {
                String key = cfg.requireString("issueKey");
                String id = cfg.requireString("commentId");
                jira.put(jira.api() + "/issue/" + JiraClient.enc(key) + "/comment/" + JiraClient.enc(id),
                        Map.of("body", jira.richText(cfg.requireString("comment"))), "comment " + id);
                out.put("issueKey", key);
                out.put("commentId", id);
            }

            case COMMENT_DELETE -> {
                String key = cfg.requireString("issueKey");
                String id = cfg.requireString("commentId");
                jira.delete(jira.api() + "/issue/" + JiraClient.enc(key) + "/comment/" + JiraClient.enc(id),
                        "comment " + id);
                out.put("issueKey", key);
                out.put("commentId", id);
            }

            // ---------------------------------------------------------- worklog
            case WORKLOG_ADD -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("timeSpent", cfg.requireString("timeSpent"));
                if (cfg.has("comment")) {
                    body.put("comment", jira.richText(cfg.getString("comment", null)));
                }
                if (cfg.has("started")) {
                    body.put("started", cfg.getString("started", null));
                }
                Map<String, Object> worklog = jira.post(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "/worklog", body, "worklog on " + key);
                out.put("issueKey", key);
                out.put("worklogId", worklog.get("id"));
            }

            case WORKLOG_LIST -> {
                String key = cfg.requireString("issueKey");
                out.put("issueKey", key);
                out.put("result", jira.get(jira.api() + "/issue/" + JiraClient.enc(key) + "/worklog",
                        "worklogs on " + key));
            }

            // ---------------------------------------------------------- attachments
            case ATTACHMENT_ADD -> {
                String key = cfg.requireString("issueKey");
                List<Object> attached = jira.attachText(key,
                        cfg.getString("fileName", "orchpilot.txt"), cfg.requireString("content"));
                out.put("issueKey", key);
                out.put("attachments", attached);
                out.put("count", attached.size());
            }

            case ATTACHMENT_LIST -> {
                String key = cfg.requireString("issueKey");
                Map<String, Object> issue = jira.get(
                        jira.api() + "/issue/" + JiraClient.enc(key) + "?fields=attachment",
                        "attachments on " + key);
                out.put("issueKey", key);
                out.put("result", issue.get("fields"));
            }

            // ---------------------------------------------------------- projects and metadata
            case PROJECT_LIST -> out.put("result",
                    jira.get(jira.api() + "/project/search?maxResults=100", "projects"));

            case PROJECT_GET -> {
                String key = cfg.requireString("projectKey");
                out.put("result", jira.get(jira.api() + "/project/" + JiraClient.enc(key), "project " + key));
            }

            case PROJECT_COMPONENTS -> {
                String key = cfg.requireString("projectKey");
                out.put("result", jira.get(jira.api() + "/project/" + JiraClient.enc(key) + "/components",
                        "components of " + key));
            }

            case PROJECT_VERSIONS -> {
                String key = cfg.requireString("projectKey");
                out.put("result", jira.get(jira.api() + "/project/" + JiraClient.enc(key) + "/versions",
                        "versions of " + key));
            }

            case ISSUE_TYPE_LIST -> out.put("result", jira.get(jira.api() + "/issuetype", "issue types"));

            case PRIORITY_LIST -> out.put("result", jira.get(jira.api() + "/priority", "priorities"));

            case STATUS_LIST -> out.put("result", jira.get(jira.api() + "/status", "statuses"));

            case USER_CURRENT -> {
                Map<String, Object> user = jira.get(jira.api() + "/myself", "the current user");
                out.put("accountId", user.get("accountId"));
                out.put("displayName", user.get("displayName"));
                out.put("emailAddress", user.get("emailAddress"));
                out.put("deployment", jira.deployment().name());
            }

            case USER_SEARCH -> {
                String query = cfg.requireString("query");
                String param = jira.deployment() == JiraClient.Deployment.CLOUD ? "query" : "username";
                out.put("result", jira.get(jira.api() + "/user/search?" + param + "=" + JiraClient.enc(query)
                        + "&maxResults=" + cfg.getInt("maxResults", 20), "user search"));
            }

            // ---------------------------------------------------------- agile
            case BOARD_LIST -> {
                String url = jira.agile() + "/board?maxResults=" + cfg.getInt("maxResults", 50);
                if (cfg.has("projectKey")) {
                    url += "&projectKeyOrId=" + JiraClient.enc(cfg.getString("projectKey", ""));
                }
                out.put("result", jira.get(url, "boards"));
            }

            case BOARD_ISSUES -> {
                String board = cfg.requireString("boardId");
                String url = jira.agile() + "/board/" + JiraClient.enc(board) + "/issue?maxResults="
                        + cfg.getInt("maxResults", 50);
                if (cfg.has("jql")) {
                    url += "&jql=" + JiraClient.enc(cfg.getString("jql", ""));
                }
                out.put("boardId", board);
                out.put("result", jira.get(url, "issues on board " + board));
            }

            case SPRINT_LIST -> {
                String board = cfg.requireString("boardId");
                String url = jira.agile() + "/board/" + JiraClient.enc(board) + "/sprint";
                if (cfg.has("state")) {
                    url += "?state=" + JiraClient.enc(cfg.getString("state", ""));
                }
                out.put("boardId", board);
                out.put("result", jira.get(url, "sprints on board " + board));
            }

            case SPRINT_GET -> {
                String sprint = cfg.requireString("sprintId");
                out.put("result", jira.get(jira.agile() + "/sprint/" + JiraClient.enc(sprint),
                        "sprint " + sprint));
            }

            case SPRINT_CREATE -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", cfg.requireString("name"));
                body.put("originBoardId", Integer.parseInt(cfg.requireString("boardId")));
                putIfPresent(body, "goal", cfg.getString("goal", null));
                putIfPresent(body, "startDate", cfg.getString("startDate", null));
                putIfPresent(body, "endDate", cfg.getString("endDate", null));
                Map<String, Object> sprint = jira.post(jira.agile() + "/sprint", body, "sprint creation");
                out.put("sprintId", sprint.get("id"));
                out.put("result", sprint);
            }

            case SPRINT_START -> {
                String sprint = cfg.requireString("sprintId");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("state", "active");
                putIfPresent(body, "startDate", cfg.getString("startDate", null));
                putIfPresent(body, "endDate", cfg.getString("endDate", null));
                out.put("sprintId", sprint);
                out.put("result", jira.post(jira.agile() + "/sprint/" + JiraClient.enc(sprint), body,
                        "sprint " + sprint));
            }

            case SPRINT_CLOSE -> {
                String sprint = cfg.requireString("sprintId");
                out.put("sprintId", sprint);
                out.put("result", jira.post(jira.agile() + "/sprint/" + JiraClient.enc(sprint),
                        Map.of("state", "closed"), "sprint " + sprint));
            }

            case SPRINT_MOVE_ISSUES -> {
                String sprint = cfg.requireString("sprintId");
                List<String> keys = csv(cfg.getString("issueKeys", ""));
                jira.post(jira.agile() + "/sprint/" + JiraClient.enc(sprint) + "/issue",
                        Map.of("issues", keys), "sprint " + sprint);
                out.put("sprintId", sprint);
                out.put("movedIssues", keys);
                out.put("count", keys.size());
            }

            // ---------------------------------------------------------- versions and components
            case VERSION_CREATE -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", cfg.requireString("name"));
                body.put("project", cfg.requireString("projectKey"));
                putIfPresent(body, "description", cfg.getString("description", null));
                putIfPresent(body, "releaseDate", cfg.getString("releaseDate", null));
                Map<String, Object> version = jira.post(jira.api() + "/version", body, "version creation");
                out.put("versionId", version.get("id"));
                out.put("result", version);
            }

            case VERSION_RELEASE -> {
                String id = cfg.requireString("versionId");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("released", true);
                putIfPresent(body, "releaseDate", cfg.getString("releaseDate", null));
                out.put("versionId", id);
                out.put("result", jira.put(jira.api() + "/version/" + JiraClient.enc(id), body,
                        "version " + id));
            }

            case COMPONENT_CREATE -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", cfg.requireString("name"));
                body.put("project", cfg.requireString("projectKey"));
                putIfPresent(body, "description", cfg.getString("description", null));
                putIfPresent(body, "leadAccountId", cfg.getString("leadAccountId", null));
                Map<String, Object> component = jira.post(jira.api() + "/component", body,
                        "component creation");
                out.put("componentId", component.get("id"));
                out.put("result", component);
            }

            case COMPONENT_DELETE -> {
                String id = cfg.requireString("componentId");
                jira.delete(jira.api() + "/component/" + JiraClient.enc(id), "component " + id);
                out.put("componentId", id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Executes a transition, resolving a human name to an id first.
     *
     * <p>Workflow authors and the AI Agent both say "In Progress", not "31". Jira's transition ids are per
     * workflow and unguessable, so a name is resolved against the transitions the issue can <em>currently</em>
     * make — which also means an impossible transition fails with a message listing what was actually possible,
     * rather than a bare 400.
     */
    private Map<String, Object> transition(JiraClient jira, NodeConfiguration cfg) {
        String key = cfg.requireString("issueKey");
        String wanted = cfg.requireString("transition");

        Map<String, Object> available = jira.get(
                jira.api() + "/issue/" + JiraClient.enc(key) + "/transitions", "transitions of " + key);
        String id = null;
        List<String> names = new ArrayList<>();
        if (available.get("transitions") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> t) {
                    String name = String.valueOf(t.get("name"));
                    String transitionId = String.valueOf(t.get("id"));
                    names.add(name);
                    if (wanted.equalsIgnoreCase(name) || wanted.equals(transitionId)) {
                        id = transitionId;
                    }
                }
            }
        }
        if (id == null) {
            throw new JiraException("JIRA_TRANSITION_NOT_AVAILABLE",
                    "Issue " + key + " cannot transition to '" + wanted + "' from its current status. "
                            + "Available now: " + names + ".", false);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transition", Map.of("id", id));
        if (cfg.has("comment")) {
            // A comment on a transition rides in the update block, not the fields block.
            body.put("update", Map.of("comment",
                    List.of(Map.of("add", Map.of("body", jira.richText(cfg.getString("comment", null)))))));
        }
        jira.post(jira.api() + "/issue/" + JiraClient.enc(key) + "/transitions", body, "issue " + key);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueKey", key);
        out.put("transition", wanted);
        out.put("transitionId", id);
        out.put("issueUrl", browseUrl(jira, key));
        return out;
    }

    /** Builds the {@code fields} block, converting text to the deployment's rich-text form. */
    private Map<String, Object> issueFields(JiraClient jira, NodeConfiguration cfg, boolean creating) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (creating) {
            fields.put("project", Map.of("key", cfg.requireString("projectKey")));
            fields.put("issuetype", Map.of("name", cfg.getString("issueType", "Task")));
            fields.put("summary", cfg.requireString("summary"));
        } else {
            putIfPresent(fields, "summary", cfg.getString("summary", null));
        }
        if (cfg.has("description")) {
            fields.put("description", jira.richText(cfg.getString("description", null)));
        }
        if (cfg.has("priority")) {
            fields.put("priority", Map.of("name", cfg.getString("priority", "")));
        }
        if (cfg.has("assignee")) {
            String assignee = cfg.getString("assignee", null);
            if (assignee != null && !assignee.isBlank()) {
                fields.put("assignee", jira.deployment() == JiraClient.Deployment.CLOUD
                        ? Map.of("accountId", assignee) : Map.of("name", assignee));
            }
        }
        List<String> labels = csv(cfg.getString("labels", ""));
        if (!labels.isEmpty()) {
            fields.put("labels", labels);
        }
        List<String> components = csv(cfg.getString("components", ""));
        if (!components.isEmpty()) {
            List<Map<String, String>> refs = new ArrayList<>();
            components.forEach(name -> refs.add(Map.of("name", name)));
            fields.put("components", refs);
        }
        if (cfg.has("fixVersion")) {
            fields.put("fixVersions", List.of(Map.of("name", cfg.getString("fixVersion", ""))));
        }
        putIfPresent(fields, "duedate", cfg.getString("dueDate", null));
        if (cfg.has("parentKey")) {
            fields.put("parent", Map.of("key", cfg.getString("parentKey", "")));
        }
        // Custom fields last, so an explicit raw field id always wins over a convenience field above it.
        fields.putAll(cfg.getMap("customFields"));
        return fields;
    }

    /** Flattens an issue into the handful of values a workflow actually branches on. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> summarise(JiraClient jira, Map<String, Object> issue) {
        Map<String, Object> out = new LinkedHashMap<>();
        String key = String.valueOf(issue.get("key"));
        out.put("issueKey", key);
        out.put("issueId", issue.get("id"));
        out.put("issueUrl", browseUrl(jira, key));

        Object rawFields = issue.get("fields");
        if (rawFields instanceof Map) {
            Map<String, Object> fields = (Map<String, Object>) rawFields;
            out.put("summary", fields.get("summary"));
            out.put("status", nameOf(fields.get("status")));
            out.put("priority", nameOf(fields.get("priority")));
            out.put("issueType", nameOf(fields.get("issuetype")));
            out.put("assignee", displayNameOf(fields.get("assignee")));
            out.put("reporter", displayNameOf(fields.get("reporter")));
            out.put("created", fields.get("created"));
            out.put("updated", fields.get("updated"));
            out.put("labels", fields.get("labels"));
        }
        return out;
    }

    private void issueIdentity(Map<String, Object> out, JiraClient jira, Map<String, Object> created) {
        String key = String.valueOf(created.get("key"));
        out.put("issueKey", key);
        out.put("issueId", created.get("id"));
        out.put("issueUrl", browseUrl(jira, key));
    }

    private static String browseUrl(JiraClient jira, String issueKey) {
        return jira.baseUrl() + "/browse/" + issueKey;
    }

    /**
     * Finds the project a cloned issue belongs to.
     *
     * <p>Prefers the project object on the source issue, but falls back to the key's own prefix — {@code ENG-42}
     * is in {@code ENG} — because a clone must not fail merely because the caller did not request the project
     * field back.
     */
    private static String projectOf(Map<String, Object> sourceFields, String issueKey) {
        if (sourceFields.get("project") instanceof Map<?, ?> project && project.get("key") != null) {
            return String.valueOf(project.get("key"));
        }
        int dash = issueKey.lastIndexOf('-');
        if (dash > 0) {
            return issueKey.substring(0, dash);
        }
        throw new JiraException("JIRA_MISCONFIGURED",
                "Cloning " + issueKey + " needs a target project key.", false);
    }

    private static String nameOf(Object value) {
        return value instanceof Map<?, ?> map && map.get("name") != null
                ? String.valueOf(map.get("name")) : null;
    }

    private static String displayNameOf(Object value) {
        return value instanceof Map<?, ?> map && map.get("displayName") != null
                ? String.valueOf(map.get("displayName")) : null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** A metadata-only audit record: who did what to which issue, never a credential. */
    private void audit(NodeExecutionContext ctx, JiraOperation operation, NodeConfiguration cfg, String status,
                       long millis) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("operation", operation.name());
            record.put("capability", operation.capability());
            record.put("riskLevel", operation.risk().name());
            record.put("workflowId", ctx.workflowId());
            record.put("workflowExecutionId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("issueKey", cfg.getString("issueKey", null));
            record.put("projectKey", cfg.getString("projectKey", null));
            record.put("status", status);
            record.put("durationMillis", millis);
            record.put("timestamp", Instant.now().toString());
            context.dataStore().put("audit", ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt(),
                    record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write Jira audit record: {}", ex.getMessage());
        }
    }
}
