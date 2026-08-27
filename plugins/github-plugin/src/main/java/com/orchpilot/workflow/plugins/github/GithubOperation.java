package com.orchpilot.workflow.plugins.github;

/**
 * The GitHub operations this plugin exposes, each as its own workflow node type.
 *
 * <h2>One operation, one node, one AI tool, one risk level</h2>
 *
 * As in the GCP plugin, modelling each operation as a distinct node type is what lets the AI Agent see each as its
 * own tool (the registry derives {@code github_merge_pull_request} from {@code GITHUB_MERGE_PULL_REQUEST}) with its
 * own {@link Risk}, mirrored onto the node's {@code destructive} flag. So a supervised agent gates a repository or
 * branch delete, or a PR merge, while reads run freely — a single node with an operation dropdown could not.
 */
public enum GithubOperation {

    // --- Repositories ---
    GET_REPOSITORY("GITHUB_GET_REPOSITORY", "Get GitHub Repository",
            "Reads a repository's metadata.", Risk.READ_ONLY),
    LIST_REPOSITORIES("GITHUB_LIST_REPOSITORIES", "List GitHub Repositories",
            "Lists repositories for an organization, or for the authenticated user when no org is given.",
            Risk.READ_ONLY),
    CREATE_REPOSITORY("GITHUB_CREATE_REPOSITORY", "Create GitHub Repository",
            "Creates a repository under an organization or the authenticated user.", Risk.MODIFY),
    UPDATE_REPOSITORY("GITHUB_UPDATE_REPOSITORY", "Update GitHub Repository",
            "Updates a repository's settings (description, visibility, features, default branch).", Risk.MODIFY),
    DELETE_REPOSITORY("GITHUB_DELETE_REPOSITORY", "Delete GitHub Repository",
            "Permanently deletes a repository. Destructive and irreversible.", Risk.DESTRUCTIVE),
    FORK_REPOSITORY("GITHUB_FORK_REPOSITORY", "Fork GitHub Repository",
            "Forks a repository into an organization or the authenticated user's account.", Risk.MODIFY),

    // --- Branches ---
    LIST_BRANCHES("GITHUB_LIST_BRANCHES", "List GitHub Branches",
            "Lists a repository's branches.", Risk.READ_ONLY),
    GET_BRANCH("GITHUB_GET_BRANCH", "Get GitHub Branch",
            "Reads a branch, including its head commit and protection state.", Risk.READ_ONLY),
    CREATE_BRANCH("GITHUB_CREATE_BRANCH", "Create GitHub Branch",
            "Creates a branch from a source branch's head commit.", Risk.MODIFY),
    DELETE_BRANCH("GITHUB_DELETE_BRANCH", "Delete GitHub Branch",
            "Deletes a branch reference. Destructive.", Risk.DESTRUCTIVE),

    // --- Files / contents ---
    GET_FILE("GITHUB_GET_FILE", "Get GitHub File",
            "Reads a file's contents (decoded) and its blob sha.", Risk.READ_ONLY),
    PUT_FILE("GITHUB_PUT_FILE", "Create or Update GitHub File",
            "Creates or updates a file with a commit. Updates require the file's current sha.", Risk.MODIFY),
    DELETE_FILE("GITHUB_DELETE_FILE", "Delete GitHub File",
            "Deletes a file with a commit. Destructive.", Risk.DESTRUCTIVE),

    // --- Commits ---
    LIST_COMMITS("GITHUB_LIST_COMMITS", "List GitHub Commits",
            "Lists commits on a repository, optionally filtered by branch/path/author.", Risk.READ_ONLY),
    GET_COMMIT("GITHUB_GET_COMMIT", "Get GitHub Commit",
            "Reads a single commit, including its files and stats.", Risk.READ_ONLY),

    // --- Pull requests ---
    CREATE_PULL_REQUEST("GITHUB_CREATE_PULL_REQUEST", "Create GitHub Pull Request",
            "Opens a pull request from a head branch into a base branch.", Risk.MODIFY),
    GET_PULL_REQUEST("GITHUB_GET_PULL_REQUEST", "Get GitHub Pull Request",
            "Reads a pull request.", Risk.READ_ONLY),
    LIST_PULL_REQUESTS("GITHUB_LIST_PULL_REQUESTS", "List GitHub Pull Requests",
            "Lists a repository's pull requests, filtered by state.", Risk.READ_ONLY),
    UPDATE_PULL_REQUEST("GITHUB_UPDATE_PULL_REQUEST", "Update GitHub Pull Request",
            "Updates a pull request's title, body, state (open/closed) or base.", Risk.MODIFY),
    MERGE_PULL_REQUEST("GITHUB_MERGE_PULL_REQUEST", "Merge GitHub Pull Request",
            "Merges a pull request. Consequential and hard to undo.", Risk.DESTRUCTIVE),
    REVIEW_PULL_REQUEST("GITHUB_REVIEW_PULL_REQUEST", "Review GitHub Pull Request",
            "Submits a review (APPROVE, REQUEST_CHANGES or COMMENT) on a pull request.", Risk.MODIFY),
    COMMENT_PULL_REQUEST("GITHUB_COMMENT_PULL_REQUEST", "Comment on GitHub Pull Request",
            "Adds a comment to a pull request's conversation.", Risk.MODIFY),

    // --- Issues ---
    CREATE_ISSUE("GITHUB_CREATE_ISSUE", "Create GitHub Issue",
            "Opens an issue.", Risk.MODIFY),
    GET_ISSUE("GITHUB_GET_ISSUE", "Get GitHub Issue",
            "Reads an issue.", Risk.READ_ONLY),
    LIST_ISSUES("GITHUB_LIST_ISSUES", "List GitHub Issues",
            "Lists a repository's issues, filtered by state/labels.", Risk.READ_ONLY),
    UPDATE_ISSUE("GITHUB_UPDATE_ISSUE", "Update GitHub Issue",
            "Updates an issue's title, body, state (open/closed), labels or assignees.", Risk.MODIFY),
    COMMENT_ISSUE("GITHUB_COMMENT_ISSUE", "Comment on GitHub Issue",
            "Adds a comment to an issue.", Risk.MODIFY),

    // --- Releases ---
    CREATE_RELEASE("GITHUB_CREATE_RELEASE", "Create GitHub Release",
            "Creates a release for a tag (creating the tag if needed).", Risk.MODIFY),
    LIST_RELEASES("GITHUB_LIST_RELEASES", "List GitHub Releases",
            "Lists a repository's releases.", Risk.READ_ONLY),

    // --- Actions / workflows ---
    DISPATCH_WORKFLOW("GITHUB_DISPATCH_WORKFLOW", "Dispatch GitHub Workflow",
            "Triggers a workflow_dispatch run on a ref, with optional inputs.", Risk.MODIFY),
    LIST_WORKFLOW_RUNS("GITHUB_LIST_WORKFLOW_RUNS", "List GitHub Workflow Runs",
            "Lists a repository's Actions workflow runs.", Risk.READ_ONLY),
    GET_WORKFLOW_RUN("GITHUB_GET_WORKFLOW_RUN", "Get GitHub Workflow Run",
            "Reads a workflow run, including its status and conclusion.", Risk.READ_ONLY),
    CANCEL_WORKFLOW_RUN("GITHUB_CANCEL_WORKFLOW_RUN", "Cancel GitHub Workflow Run",
            "Cancels an in-progress workflow run.", Risk.MODIFY),
    RERUN_WORKFLOW_RUN("GITHUB_RERUN_WORKFLOW_RUN", "Re-run GitHub Workflow Run",
            "Re-runs a workflow run.", Risk.MODIFY),

    // --- Search ---
    SEARCH_REPOSITORIES("GITHUB_SEARCH_REPOSITORIES", "Search GitHub Repositories",
            "Searches repositories with GitHub's search syntax.", Risk.READ_ONLY),
    SEARCH_CODE("GITHUB_SEARCH_CODE", "Search GitHub Code",
            "Searches code with GitHub's code-search syntax.", Risk.READ_ONLY);

    /** Sensitivity of an operation, mirrored onto the node's {@code destructive} flag for the AI Agent. */
    public enum Risk {
        READ_ONLY,
        MODIFY,
        DESTRUCTIVE
    }

    private final String nodeType;
    private final String displayName;
    private final String description;
    private final Risk risk;

    GithubOperation(String nodeType, String displayName, String description, Risk risk) {
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.description = description;
        this.risk = risk;
    }

    public String nodeType() {
        return nodeType;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public Risk risk() {
        return risk;
    }

    public boolean destructive() {
        return risk == Risk.DESTRUCTIVE;
    }

    public static GithubOperation forNodeType(String nodeType) {
        for (GithubOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
