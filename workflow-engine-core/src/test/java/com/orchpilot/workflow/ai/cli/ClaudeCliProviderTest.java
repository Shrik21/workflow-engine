package com.orchpilot.workflow.ai.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the Claude CLI is invoked and how its output is read.
 */
class ClaudeCliProviderTest {

    private final ClaudeCliProvider provider = new ClaudeCliProvider();

    private static ProcessResult out(String stdout) {
        return new ProcessResult(0, stdout, "", false, 10);
    }

    @Test
    @DisplayName("identifies itself for the registry and detection")
    void identity() {
        assertThat(provider.type()).isEqualTo(AiCliType.CLAUDE_CLI);
        assertThat(provider.command()).isEqualTo("claude");
        assertThat(provider.displayName()).isEqualTo("Claude CLI");
    }

    @Test
    @DisplayName("always runs non-interactively, and never puts the prompt in an argument")
    void promptArguments() {
        assertThat(provider.promptArguments(false)).containsExactly("--print");
        assertThat(provider.promptArguments(true))
                .containsExactly("--print", "--output-format", "json");

        // The prompt is never an argument: an error message starting with a dash would become a flag.
        assertThat(provider.promptArguments(true)).noneMatch(a -> a.length() > 20);
    }

    @Test
    @DisplayName("reads a version from either stream")
    void parsesVersion() {
        assertThat(provider.parseVersion(out("1.0.60 (Claude Code)\n"))).isEqualTo("1.0.60 (Claude Code)");
        assertThat(provider.parseVersion(new ProcessResult(0, "", "2.1.3\n", false, 5))).isEqualTo("2.1.3");
    }

    @Test
    @DisplayName("returns null when there is no version to find")
    void parsesNoVersion() {
        assertThat(provider.parseVersion(out("command not found"))).isNull();
        assertThat(provider.parseVersion(out(""))).isNull();
    }

    @Test
    @DisplayName("returns plain output as the answer")
    void parsesPlainResponse() {
        assertThat(provider.parseResponse(out("The service account lacks the permission.\n")))
                .isEqualTo("The service account lacks the permission.");
    }

    @Test
    @DisplayName("unwraps the JSON envelope")
    void parsesJsonEnvelope() {
        String json = "{\"type\":\"result\",\"is_error\":false,\"result\":\"Grant networkAdmin.\","
                + "\"session_id\":\"abc\"}";

        assertThat(provider.parseResponse(out(json))).isEqualTo("Grant networkAdmin.");
    }

    @Test
    @DisplayName("treats an error envelope as a failure even though the process exited 0")
    void detectsErrorEnvelope() {
        String json = "{\"is_error\":true,\"result\":\"rate limit exceeded\"}";

        assertThatThrownBy(() -> provider.parseResponse(out(json)))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    @DisplayName("a non-zero exit is a failure, and the diagnostic is summarised not dumped")
    void failsOnNonZeroExit() {
        ProcessResult result = new ProcessResult(1, "", "not logged in\n", false, 5);

        assertThatThrownBy(() -> provider.parseResponse(result))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_EXECUTION_FAILED"))
                .hasMessageContaining("not logged in");
    }

    @Test
    @DisplayName("empty output is reported rather than returned as an empty answer")
    void failsOnEmptyOutput() {
        assertThatThrownBy(() -> provider.parseResponse(out("   ")))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_EMPTY_RESPONSE"));
    }

    @Test
    @DisplayName("truncated JSON says so, because that is the likely cause")
    void reportsTruncation() {
        ProcessResult truncated = new ProcessResult(0, "{\"result\":\"half an ans", "", true, 5);

        assertThatThrownBy(() -> provider.parseResponse(truncated))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("truncated");
    }
}
