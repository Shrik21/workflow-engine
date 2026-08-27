package com.orchpilot.workflow.plugins.jira;

import com.orchpilot.workflow.plugins.jira.model.JiraOperation;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * The designer form for each node.
 *
 * <p>The engine's designer renders whatever a node declares here, so "the form fields change according to the
 * operation" is satisfied without a single hand-written component: each operation is its own node and declares
 * exactly its own fields. Every value accepts {@code ${…}}, so a Form node, a previous node's output or an AI
 * Agent result feeds straight in.
 */
final class JiraSchemas {

    private JiraSchemas() {
    }

    static Map<String, Object> forOperation(JiraOperation operation) {
        SchemaBuilder s = connection();

        switch (operation) {
            case ISSUE_CREATE -> s.string("projectKey", "Project key", true)
                    .withDescription("projectKey", "For example ENG. Supports ${variables}.")
                    .string("issueType", "Issue type", true).withDefault("issueType", "Task")
                    .string("summary", "Summary", true)
                    .text("description", "Description", false)
                    .withDescription("description",
                            "Plain text. Converted to Atlassian Document Format automatically on Jira Cloud.")
                    .string("priority", "Priority", false)
                    .string("assignee", "Assignee (account id, or email on Server)", false)
                    .string("labels", "Labels (comma separated)", false)
                    .string("components", "Components (comma separated)", false)
                    .string("fixVersion", "Fix version", false)
                    .string("dueDate", "Due date (yyyy-MM-dd)", false)
                    .string("parentKey", "Parent issue key (for a sub-task)", false)
                    .map("customFields", "Custom fields", false)
                    .withDescription("customFields",
                            "Raw Jira field ids to values, e.g. customfield_10010, for anything above not covered.");

            case ISSUE_GET, ISSUE_CHANGELOG, TRANSITION_LIST, COMMENT_LIST, WORKLOG_LIST, ATTACHMENT_LIST ->
                    s.string("issueKey", "Issue key", true).withDescription("issueKey", "For example ENG-123.");

            case ISSUE_DELETE -> s.string("issueKey", "Issue key", true)
                    .bool("deleteSubtasks", "Also delete sub-tasks", false)
                    .withDefault("deleteSubtasks", false);

            case ISSUE_UPDATE -> s.string("issueKey", "Issue key", true)
                    .string("summary", "Summary", false)
                    .text("description", "Description", false)
                    .string("priority", "Priority", false)
                    .string("labels", "Labels (comma separated)", false)
                    .string("dueDate", "Due date (yyyy-MM-dd)", false)
                    .map("customFields", "Custom fields", false);

            case ISSUE_SEARCH -> s.text("jql", "JQL", true)
                    .withDescription("jql",
                            "For example: project = ENG AND status = \"In Progress\" ORDER BY created DESC. "
                                    + "The AI Agent generates this from natural language.")
                    .integer("startAt", "Start at", false).withDefault("startAt", 0)
                    .integer("maxResults", "Maximum results", false).withDefault("maxResults", 50)
                    .string("fields", "Fields (comma separated)", false)
                    .withDescription("fields", "Blank returns a useful default set.");

            case ISSUE_ASSIGN -> s.string("issueKey", "Issue key", true)
                    .string("assignee", "Assignee account id", false)
                    .withDescription("assignee",
                            "Leave blank to unassign. Use Search Jira Users to turn a name into an account id.");

            case ISSUE_TRANSITION -> s.string("issueKey", "Issue key", true)
                    .string("transition", "Transition", true)
                    .withDescription("transition",
                            "The transition name (for example 'In Progress') or its id. Names are resolved "
                                    + "against what the issue can actually do right now.")
                    .text("comment", "Comment to add with the transition", false);

            case ISSUE_CLONE -> s.string("issueKey", "Issue key to clone", true)
                    .string("summary", "New summary", false)
                    .string("projectKey", "Target project key", false);

            case COMMENT_ADD -> s.string("issueKey", "Issue key", true)
                    .text("comment", "Comment", true);

            case COMMENT_UPDATE -> s.string("issueKey", "Issue key", true)
                    .string("commentId", "Comment id", true)
                    .text("comment", "Comment", true);

            case COMMENT_DELETE -> s.string("issueKey", "Issue key", true)
                    .string("commentId", "Comment id", true);

            case WORKLOG_ADD -> s.string("issueKey", "Issue key", true)
                    .string("timeSpent", "Time spent", true)
                    .withDescription("timeSpent", "Jira duration syntax, for example 3h 30m.")
                    .text("comment", "Worklog comment", false)
                    .string("started", "Started (ISO-8601)", false);

            case ATTACHMENT_ADD -> s.string("issueKey", "Issue key", true)
                    .string("fileName", "File name", true).withDefault("fileName", "orchpilot.txt")
                    .text("content", "File content", true)
                    .withDescription("content",
                            "Text only — a log, a report, JSON. Typically ${a previous node's output}.");

            case PROJECT_GET, PROJECT_COMPONENTS, PROJECT_VERSIONS ->
                    s.string("projectKey", "Project key", true);

            case PROJECT_LIST, PRIORITY_LIST, STATUS_LIST, USER_CURRENT -> { /* connection only */ }

            case ISSUE_TYPE_LIST -> s.string("projectKey", "Project key (optional)", false);

            case USER_SEARCH -> s.string("query", "Name or email", true)
                    .integer("maxResults", "Maximum results", false).withDefault("maxResults", 20);

            case BOARD_LIST -> s.string("projectKey", "Project key (optional)", false)
                    .integer("maxResults", "Maximum results", false).withDefault("maxResults", 50);

            case BOARD_ISSUES -> s.string("boardId", "Board id", true)
                    .text("jql", "Extra JQL (optional)", false)
                    .integer("maxResults", "Maximum results", false).withDefault("maxResults", 50);

            case SPRINT_LIST -> s.string("boardId", "Board id", true)
                    .select("state", "State", List.of("active", "future", "closed"), false);

            case SPRINT_GET, SPRINT_CLOSE -> s.string("sprintId", "Sprint id", true);

            case SPRINT_CREATE -> s.string("boardId", "Board id", true)
                    .string("name", "Sprint name", true)
                    .string("goal", "Sprint goal", false)
                    .string("startDate", "Start date (ISO-8601)", false)
                    .string("endDate", "End date (ISO-8601)", false);

            case SPRINT_START -> s.string("sprintId", "Sprint id", true)
                    .string("startDate", "Start date (ISO-8601)", false)
                    .string("endDate", "End date (ISO-8601)", false);

            case SPRINT_MOVE_ISSUES -> s.string("sprintId", "Sprint id", true)
                    .string("issueKeys", "Issue keys (comma separated)", true);

            case VERSION_CREATE -> s.string("projectKey", "Project key", true)
                    .string("name", "Version name", true)
                    .string("description", "Description", false)
                    .string("releaseDate", "Release date (yyyy-MM-dd)", false);

            case VERSION_RELEASE -> s.string("versionId", "Version id", true)
                    .string("releaseDate", "Release date (yyyy-MM-dd)", false);

            case COMPONENT_CREATE -> s.string("projectKey", "Project key", true)
                    .string("name", "Component name", true)
                    .string("description", "Description", false)
                    .string("leadAccountId", "Lead account id", false);

            case COMPONENT_DELETE -> s.string("componentId", "Component id", true);
        }
        return s.build();
    }

    private static SchemaBuilder connection() {
        return SchemaBuilder.object()
                .string("baseUrl", "Jira base URL", true)
                .withDescription("baseUrl", "For example https://company.atlassian.net.")
                .select("deployment", "Deployment", List.of("CLOUD", "SERVER"), false)
                .withDefault("deployment", "CLOUD")
                .withDescription("deployment",
                        "Cloud uses REST v3 and Atlassian Document Format; Server/Data Center uses v2 and "
                                + "plain text. The plugin adapts automatically.")
                .secretRef("credentialsSecret", "Jira credentials secret name", true)
                .withDescription("credentialsSecret",
                        "The NAME of a secret (prefix jira.), never a credential. Cloud: 'email:apiToken'. "
                                + "Server/Data Center: the personal access token on its own.");
    }
}
