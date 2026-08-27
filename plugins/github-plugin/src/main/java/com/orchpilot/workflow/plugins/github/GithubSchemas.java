package com.orchpilot.workflow.plugins.github;

import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * Builds the designer configuration schema for each GitHub node, sharing the common credential and repository
 * fields so every node presents the same header and only its own operation-specific inputs below it.
 */
final class GithubSchemas {

    private GithubSchemas() {
    }

    static Map<String, Object> forOperation(GithubOperation op) {
        SchemaBuilder s = base();
        switch (op) {
            case GET_REPOSITORY, DELETE_REPOSITORY, LIST_BRANCHES, LIST_RELEASES -> repo(s);
            case LIST_REPOSITORIES -> s.string("org", "Organization (blank = your repos)", false)
                    .select("type", "Type", List.of("all", "owner", "public", "private", "member"), false)
                    .integer("per_page", "Per page", false);
            case CREATE_REPOSITORY -> s.string("org", "Organization (blank = your account)", false)
                    .string("name", "Repository name", true)
                    .string("description", "Description", false)
                    .bool("private", "Private", false).withDefault("private", false)
                    .bool("auto_init", "Initialize with README", false).withDefault("auto_init", true)
                    .bool("has_issues", "Enable issues", false)
                    .bool("has_wiki", "Enable wiki", false);
            case UPDATE_REPOSITORY -> repo(s).string("description", "Description", false)
                    .string("default_branch", "Default branch", false)
                    .select("visibility", "Visibility", List.of("public", "private", "internal"), false)
                    .bool("archived", "Archived", false);
            case FORK_REPOSITORY -> repo(s).string("organization", "Fork into organization (optional)", false);

            case GET_BRANCH, DELETE_BRANCH -> repo(s).string("branch", "Branch", true);
            case CREATE_BRANCH -> repo(s).string("sourceBranch", "Source branch", false)
                    .withDefault("sourceBranch", "main").string("newBranch", "New branch", true);

            case GET_FILE -> repo(s).string("path", "File path", true).string("ref", "Ref (branch/tag/sha)", false);
            case PUT_FILE -> repo(s).string("path", "File path", true)
                    .string("message", "Commit message", true)
                    .text("content", "File content", true)
                    .string("sha", "Existing file sha (required to update)", false)
                    .string("branch", "Branch", false);
            case DELETE_FILE -> repo(s).string("path", "File path", true)
                    .string("message", "Commit message", true)
                    .string("sha", "File sha", true).string("branch", "Branch", false);

            case LIST_COMMITS -> repo(s).string("sha", "Branch or sha", false).string("path", "Path", false)
                    .string("author", "Author", false).integer("per_page", "Per page", false);
            case GET_COMMIT -> repo(s).string("sha", "Commit sha", true);

            case CREATE_PULL_REQUEST -> repo(s).string("title", "Title", true)
                    .string("head", "Head branch", true).string("base", "Base branch", true)
                    .text("body", "Description", false).bool("draft", "Draft", false);
            case GET_PULL_REQUEST -> repo(s).string("number", "Pull request number", true);
            case LIST_PULL_REQUESTS -> repo(s)
                    .select("state", "State", List.of("open", "closed", "all"), false).withDefault("state", "open")
                    .integer("per_page", "Per page", false);
            case UPDATE_PULL_REQUEST -> repo(s).string("number", "Pull request number", true)
                    .string("title", "Title", false).text("body", "Body", false)
                    .select("state", "State", List.of("open", "closed"), false).string("base", "Base branch", false);
            case MERGE_PULL_REQUEST -> repo(s).string("number", "Pull request number", true)
                    .string("commit_title", "Commit title", false).text("commit_message", "Commit message", false)
                    .select("merge_method", "Merge method", List.of("merge", "squash", "rebase"), false)
                    .withDefault("merge_method", "merge");
            case REVIEW_PULL_REQUEST -> repo(s).string("number", "Pull request number", true)
                    .select("event", "Review", List.of("APPROVE", "REQUEST_CHANGES", "COMMENT"), true)
                    .text("body", "Review comment", false);
            case COMMENT_PULL_REQUEST -> repo(s).string("number", "Pull request number", true)
                    .text("body", "Comment", true);

            case CREATE_ISSUE -> repo(s).string("title", "Title", true).text("body", "Description", false)
                    .string("labels", "Labels (comma separated)", false)
                    .string("assignees", "Assignees (comma separated)", false);
            case GET_ISSUE -> repo(s).string("number", "Issue number", true);
            case LIST_ISSUES -> repo(s)
                    .select("state", "State", List.of("open", "closed", "all"), false).withDefault("state", "open")
                    .string("labels", "Labels (comma separated)", false).integer("per_page", "Per page", false);
            case UPDATE_ISSUE -> repo(s).string("number", "Issue number", true)
                    .string("title", "Title", false).text("body", "Body", false)
                    .select("state", "State", List.of("open", "closed"), false)
                    .string("labels", "Labels (comma separated)", false)
                    .string("assignees", "Assignees (comma separated)", false);
            case COMMENT_ISSUE -> repo(s).string("number", "Issue number", true).text("body", "Comment", true);

            case CREATE_RELEASE -> repo(s).string("tag_name", "Tag name", true)
                    .string("target_commitish", "Target (branch/sha)", false).string("name", "Release name", false)
                    .text("body", "Release notes", false).bool("draft", "Draft", false)
                    .bool("prerelease", "Pre-release", false);

            case DISPATCH_WORKFLOW -> repo(s).string("workflowId", "Workflow file or id", true)
                    .string("ref", "Ref (branch/tag)", true).map("inputs", "Inputs", false);
            case LIST_WORKFLOW_RUNS -> repo(s).string("branch", "Branch", false)
                    .select("status", "Status", List.of("queued", "in_progress", "completed"), false)
                    .integer("per_page", "Per page", false);
            case GET_WORKFLOW_RUN, CANCEL_WORKFLOW_RUN, RERUN_WORKFLOW_RUN ->
                    repo(s).string("runId", "Workflow run id", true);

            case SEARCH_REPOSITORIES, SEARCH_CODE -> s.string("q", "Query", true)
                    .string("sort", "Sort", false).integer("per_page", "Per page", false);
        }
        return s.build();
    }

    private static SchemaBuilder base() {
        return SchemaBuilder.object()
                .secretRef("credentialsSecret", "GitHub token secret name", true)
                .withDescription("credentialsSecret",
                        "The NAME of a secret holding a Personal Access Token (prefix github.). Never the token.")
                .string("githubApiUrl", "GitHub API URL (blank = github.com)", false)
                .withDescription("githubApiUrl",
                        "For GitHub Enterprise Server, e.g. https://ghe.example.com/api/v3.");
    }

    private static SchemaBuilder repo(SchemaBuilder s) {
        return s.string("owner", "Owner (user or org)", true).string("repo", "Repository", true);
    }
}
