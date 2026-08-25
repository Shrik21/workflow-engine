package com.orchpilot.workflow.ai.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives Anthropic's Claude Code CLI.
 *
 * <h2>How the CLI is invoked</h2>
 *
 * Always in non-interactive print mode ({@code --print}), and always with the prompt on <b>standard input</b>
 * rather than as an argument. That is not a style choice: a prompt built from an error message contains
 * whatever the failing system wrote, so passing it as an argument would let a message beginning with a dash be
 * read as a flag. On stdin it is unambiguously data.
 *
 * <p>Structured requests add {@code --output-format json}, which wraps the answer in an envelope carrying the
 * result and an error indicator, so a failure inside the CLI can be told apart from a failure to run it.
 *
 * <h2>Credentials</h2>
 *
 * None are passed. The Claude CLI holds its own login for the account the engine runs as, which is why this
 * provider never sees, stores or forwards a key — and why nothing here can leak one.
 */
@Component
public class ClaudeCliProvider implements AiCliProvider {

    /** Matches the leading semantic version in output like {@code 1.0.60 (Claude Code)}. */
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?(?:-[\\w.]+)?)");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return AiCliType.CLAUDE_CLI;
    }

    @Override
    public String displayName() {
        return "Claude CLI";
    }

    @Override
    public String command() {
        return "claude";
    }

    @Override
    public List<String> versionArguments() {
        return List.of("--version");
    }

    @Override
    public String parseVersion(ProcessResult result) {
        // Some builds print the version to stderr; take whichever stream carries it.
        String text = result.stdout() == null || result.stdout().isBlank() ? result.stderr() : result.stdout();
        if (text == null || text.isBlank()) {
            return null;
        }
        String firstLine = text.strip().lines().findFirst().orElse("").strip();
        Matcher matcher = VERSION.matcher(firstLine);
        // The whole first line is kept, not just the number: "1.0.60 (Claude Code)" identifies the build, and
        // an operator comparing two machines wants the difference to be visible.
        return matcher.find() ? firstLine : null;
    }

    @Override
    public List<String> promptArguments(boolean jsonOutput) {
        return jsonOutput
                ? List.of("--print", "--output-format", "json")
                : List.of("--print");
    }

    @Override
    public String parseResponse(ProcessResult result) {
        if (!result.isSuccess()) {
            throw new AiCliException("AI_CLI_EXECUTION_FAILED",
                    "The Claude CLI exited with code " + result.exitCode() + ": " + summarise(result));
        }
        String stdout = result.stdout() == null ? "" : result.stdout().strip();
        if (stdout.isEmpty()) {
            throw new AiCliException("AI_CLI_EMPTY_RESPONSE",
                    "The Claude CLI produced no output. " + summarise(result));
        }
        if (!stdout.startsWith("{")) {
            // Plain --print mode: the answer is the output.
            return stdout;
        }
        try {
            JsonNode root = mapper.readTree(stdout);
            if (root.path("is_error").asBoolean(false)) {
                throw new AiCliException("AI_CLI_EXECUTION_FAILED",
                        "Claude reported an error: " + root.path("result").asText("(no detail)"));
            }
            JsonNode result_ = root.get("result");
            if (result_ != null && !result_.isNull()) {
                return result_.asText();
            }
            // An envelope we do not recognise is still output; returning it beats discarding the answer.
            return stdout;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Output that starts with '{' but is not JSON is most often a truncated response.
            throw new AiCliException("AI_CLI_BAD_RESPONSE",
                    "The Claude CLI returned output that could not be parsed as JSON"
                            + (result.truncated() ? " — it was truncated at the configured output limit." : "."));
        }
    }

    /** A short, safe description of what went wrong. Never the whole stream, which could be large. */
    private static String summarise(ProcessResult result) {
        String diagnostic = result.diagnostic();
        if (diagnostic.isEmpty()) {
            return "There was no output on either stream.";
        }
        String firstLines = diagnostic.lines().limit(5).reduce((a, b) -> a + " " + b).orElse(diagnostic);
        return firstLines.length() > 500 ? firstLines.substring(0, 500) + "…" : firstLines;
    }
}
