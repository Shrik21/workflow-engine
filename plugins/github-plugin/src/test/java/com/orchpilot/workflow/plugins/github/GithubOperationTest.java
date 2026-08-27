package com.orchpilot.workflow.plugins.github;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Risk classification — the contract the AI Agent relies on. Only the genuinely irreversible/consequential
 * operations (deleting a repo, branch or file, and merging a PR) are destructive; reads and ordinary writes are
 * not. Also pins that node types map to the {@code github_*} tool names.
 */
class GithubOperationTest {

    @Test
    void onlyIrreversibleOperationsAreDestructive() {
        Set<GithubOperation> destructive = EnumSet.of(
                GithubOperation.DELETE_REPOSITORY, GithubOperation.DELETE_BRANCH, GithubOperation.DELETE_FILE,
                GithubOperation.MERGE_PULL_REQUEST);
        for (GithubOperation op : GithubOperation.values()) {
            assertThat(op.destructive()).as("%s destructive", op).isEqualTo(destructive.contains(op));
        }
    }

    @Test
    void readsAreReadOnly() {
        assertThat(GithubOperation.GET_REPOSITORY.risk()).isEqualTo(GithubOperation.Risk.READ_ONLY);
        assertThat(GithubOperation.LIST_ISSUES.risk()).isEqualTo(GithubOperation.Risk.READ_ONLY);
        assertThat(GithubOperation.SEARCH_CODE.risk()).isEqualTo(GithubOperation.Risk.READ_ONLY);
        assertThat(GithubOperation.CREATE_ISSUE.risk()).isEqualTo(GithubOperation.Risk.MODIFY);
    }

    @Test
    void nodeTypesMapToToolNames() {
        assertThat(toolName(GithubOperation.MERGE_PULL_REQUEST)).isEqualTo("github_merge_pull_request");
        assertThat(toolName(GithubOperation.CREATE_ISSUE)).isEqualTo("github_create_issue");
        assertThat(GithubOperation.forNodeType("GITHUB_DELETE_REPOSITORY"))
                .isEqualTo(GithubOperation.DELETE_REPOSITORY);
        assertThat(GithubOperation.forNodeType("NOPE")).isNull();
    }

    private static String toolName(GithubOperation op) {
        return op.nodeType().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
    }
}
