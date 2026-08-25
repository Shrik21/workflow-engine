package com.orchpilot.workflow.plugins.jira.model;

/**
 * Every Jira capability this plugin exposes — each one a workflow node, an AI tool, and a risk level.
 *
 * <h2>One node per operation</h2>
 *
 * A single "Jira Operation" node with an operation dropdown would carry one risk flag, so the AI Agent could not
 * tell a JQL search from a delete and the risk table below could not be enforced at all. Splitting by operation
 * gives each capability its own gate. It costs nothing in UI work: the designer renders each node from its
 * declared schema, so there are no hand-written components for any of these.
 *
 * <h2>Risk drives approval</h2>
 *
 * {@link RiskLevel#HIGH} maps onto the node's {@code destructive} flag, so the platform's existing approval
 * policy stops a supervised agent from deleting an issue, a component or a version without a human. Reads and
 * ordinary writes run freely — an agent that needs approval to add a comment is an agent nobody will use.
 */
public enum JiraOperation {

    // ---------------------------------------------------------------- issues
    ISSUE_CREATE("JIRA_CREATE_ISSUE", "Create Jira Issue", "jira.issue.create",
            "Creates an issue in a project, with summary, description, type, priority, assignee, labels and "
                    + "components.", RiskLevel.MEDIUM),
    ISSUE_GET("JIRA_GET_ISSUE", "Get Jira Issue", "jira.issue.get",
            "Reads one issue by key, with its fields, status and assignee.", RiskLevel.READ_ONLY),
    ISSUE_UPDATE("JIRA_UPDATE_ISSUE", "Update Jira Issue", "jira.issue.update",
            "Updates an existing issue's fields.", RiskLevel.MEDIUM),
    ISSUE_DELETE("JIRA_DELETE_ISSUE", "Delete Jira Issue", "jira.issue.delete",
            "Permanently deletes an issue. Irreversible.", RiskLevel.HIGH),
    ISSUE_SEARCH("JIRA_SEARCH_ISSUES", "Search Jira Issues (JQL)", "jira.issue.search",
            "Searches issues with JQL, the query language the AI Agent generates from natural language.",
            RiskLevel.READ_ONLY),
    ISSUE_ASSIGN("JIRA_ASSIGN_ISSUE", "Assign Jira Issue", "jira.issue.assign",
            "Assigns an issue to a user, or clears the assignee.", RiskLevel.MEDIUM),
    ISSUE_TRANSITION("JIRA_TRANSITION_ISSUE", "Transition Jira Issue", "jira.transition.execute",
            "Moves an issue through its workflow, e.g. to In Progress or Done.", RiskLevel.MEDIUM),
    ISSUE_CLONE("JIRA_CLONE_ISSUE", "Clone Jira Issue", "jira.issue.clone",
            "Creates a copy of an issue in the same or another project.", RiskLevel.MEDIUM),
    ISSUE_CHANGELOG("JIRA_GET_CHANGELOG", "Get Jira Issue Changelog", "jira.issue.changelog",
            "Reads an issue's change history.", RiskLevel.READ_ONLY),
    TRANSITION_LIST("JIRA_LIST_TRANSITIONS", "List Jira Transitions", "jira.transition.list",
            "Lists the transitions available on an issue right now — what it may move to from its current "
                    + "status.", RiskLevel.READ_ONLY),

    // ---------------------------------------------------------------- comments
    COMMENT_ADD("JIRA_ADD_COMMENT", "Add Jira Comment", "jira.comment.create",
            "Adds a comment to an issue.", RiskLevel.LOW),
    COMMENT_LIST("JIRA_LIST_COMMENTS", "List Jira Comments", "jira.comment.list",
            "Lists an issue's comments.", RiskLevel.READ_ONLY),
    COMMENT_UPDATE("JIRA_UPDATE_COMMENT", "Update Jira Comment", "jira.comment.update",
            "Edits an existing comment.", RiskLevel.MEDIUM),
    COMMENT_DELETE("JIRA_DELETE_COMMENT", "Delete Jira Comment", "jira.comment.delete",
            "Deletes a comment. Irreversible.", RiskLevel.HIGH),

    // ---------------------------------------------------------------- worklog
    WORKLOG_ADD("JIRA_ADD_WORKLOG", "Add Jira Worklog", "jira.worklog.create",
            "Logs time against an issue.", RiskLevel.LOW),
    WORKLOG_LIST("JIRA_LIST_WORKLOGS", "List Jira Worklogs", "jira.worklog.list",
            "Lists the time logged against an issue.", RiskLevel.READ_ONLY),

    // ---------------------------------------------------------------- attachments
    ATTACHMENT_ADD("JIRA_ADD_ATTACHMENT", "Attach Text File to Jira Issue", "jira.attachment.add",
            "Attaches a text file — a log, a report, JSON — built from workflow data. Text only: binary "
                    + "uploads cannot pass through the plugin HTTP client.", RiskLevel.LOW),
    ATTACHMENT_LIST("JIRA_LIST_ATTACHMENTS", "List Jira Attachments", "jira.attachment.list",
            "Lists an issue's attachments with their names, sizes and authors.", RiskLevel.READ_ONLY),

    // ---------------------------------------------------------------- projects
    PROJECT_LIST("JIRA_LIST_PROJECTS", "List Jira Projects", "jira.project.list",
            "Lists the projects the credentials can see.", RiskLevel.READ_ONLY),
    PROJECT_GET("JIRA_GET_PROJECT", "Get Jira Project", "jira.project.get",
            "Reads one project, with its lead, type and categories.", RiskLevel.READ_ONLY),
    PROJECT_COMPONENTS("JIRA_LIST_COMPONENTS", "List Jira Components", "jira.component.list",
            "Lists a project's components.", RiskLevel.READ_ONLY),
    PROJECT_VERSIONS("JIRA_LIST_VERSIONS", "List Jira Versions", "jira.version.list",
            "Lists a project's versions.", RiskLevel.READ_ONLY),

    // ---------------------------------------------------------------- users and metadata
    USER_SEARCH("JIRA_SEARCH_USERS", "Search Jira Users", "jira.user.search",
            "Finds users by name or email — how an agent resolves 'assign it to John' to an account id.",
            RiskLevel.READ_ONLY),
    USER_CURRENT("JIRA_CURRENT_USER", "Get Current Jira User", "jira.user.current",
            "Reads the account the connection authenticates as. Doubles as a connection test.",
            RiskLevel.READ_ONLY),
    ISSUE_TYPE_LIST("JIRA_LIST_ISSUE_TYPES", "List Jira Issue Types", "jira.issueType.list",
            "Lists the issue types available, optionally for one project.", RiskLevel.READ_ONLY),
    PRIORITY_LIST("JIRA_LIST_PRIORITIES", "List Jira Priorities", "jira.priority.list",
            "Lists the priorities configured on this Jira.", RiskLevel.READ_ONLY),
    STATUS_LIST("JIRA_LIST_STATUSES", "List Jira Statuses", "jira.status.list",
            "Lists the statuses configured on this Jira.", RiskLevel.READ_ONLY),

    // ---------------------------------------------------------------- agile
    BOARD_LIST("JIRA_LIST_BOARDS", "List Jira Boards", "jira.board.list",
            "Lists Jira Software boards.", RiskLevel.READ_ONLY),
    BOARD_ISSUES("JIRA_LIST_BOARD_ISSUES", "List Jira Board Issues", "jira.board.issues",
            "Lists the issues on a board.", RiskLevel.READ_ONLY),
    SPRINT_LIST("JIRA_LIST_SPRINTS", "List Jira Sprints", "jira.sprint.list",
            "Lists a board's sprints, optionally filtered by state.", RiskLevel.READ_ONLY),
    SPRINT_GET("JIRA_GET_SPRINT", "Get Jira Sprint", "jira.sprint.get",
            "Reads one sprint.", RiskLevel.READ_ONLY),
    SPRINT_CREATE("JIRA_CREATE_SPRINT", "Create Jira Sprint", "jira.sprint.create",
            "Creates a sprint on a board.", RiskLevel.MEDIUM),
    SPRINT_START("JIRA_START_SPRINT", "Start Jira Sprint", "jira.sprint.start",
            "Starts a sprint, setting its dates and state.", RiskLevel.MEDIUM),
    SPRINT_CLOSE("JIRA_CLOSE_SPRINT", "Close Jira Sprint", "jira.sprint.close",
            "Closes a sprint.", RiskLevel.MEDIUM),
    SPRINT_MOVE_ISSUES("JIRA_MOVE_ISSUES_TO_SPRINT", "Move Issues Into Jira Sprint", "jira.sprint.moveIssues",
            "Moves issues into a sprint.", RiskLevel.MEDIUM),

    // ---------------------------------------------------------------- versions and components
    VERSION_CREATE("JIRA_CREATE_VERSION", "Create Jira Version", "jira.version.create",
            "Creates a version in a project.", RiskLevel.MEDIUM),
    VERSION_RELEASE("JIRA_RELEASE_VERSION", "Release Jira Version", "jira.version.release",
            "Marks a version released.", RiskLevel.MEDIUM),
    COMPONENT_CREATE("JIRA_CREATE_COMPONENT", "Create Jira Component", "jira.component.create",
            "Creates a component in a project.", RiskLevel.MEDIUM),
    COMPONENT_DELETE("JIRA_DELETE_COMPONENT", "Delete Jira Component", "jira.component.delete",
            "Deletes a component. Irreversible.", RiskLevel.HIGH);

    /** How consequential an operation is; HIGH becomes the node's {@code destructive} flag. */
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

    JiraOperation(String nodeType, String displayName, String capability, String description, RiskLevel risk) {
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

    public boolean destructive() {
        return risk == RiskLevel.HIGH;
    }

    public static JiraOperation forNodeType(String nodeType) {
        for (JiraOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
