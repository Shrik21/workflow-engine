package com.orchpilot.workflow.ai.analysis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes anything that looks like a credential from text on its way to an AI tool.
 *
 * <h2>Why pattern matching, when the engine already has {@code SecretRedactor}</h2>
 *
 * {@code SecretRedactor} masks values it was <em>told</em> about — it is handed each secret as it is resolved,
 * and blanks those exact strings. That is exact and reliable, and it is the right tool inside an execution.
 *
 * <p>It cannot help here. The text being sent for analysis is an error message produced by some other system,
 * and a credential inside it did not come through this engine's secret store: a cloud API that echoes back part
 * of a request, a stack trace holding a connection string, a CLI that printed a token in its diagnostics. There
 * is no known value to match, so the only available control is recognising the <em>shape</em> of a credential.
 *
 * <p>This is therefore best-effort by nature, and is a second line rather than the first — the first is that
 * {@link ErrorAnalysisContext} carries a fixed, curated set of fields rather than an arbitrary bag. Anything
 * that trips a pattern here should be treated as a bug in whatever put it there.
 */
@Component
public class SensitiveTextScrubber {

    /** What a redacted span is replaced with. Length-independent, so nothing is inferable from the mask. */
    public static final String MASK = "[REDACTED]";

    private record Rule(String name, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            // PEM blocks: a private key that reached a log is the single worst thing to forward.
            new Rule("pem", Pattern.compile(
                    "-----BEGIN[^-]{0,60}-----.*?-----END[^-]{0,60}-----", Pattern.DOTALL)),
            // Bearer / Authorization headers.
            new Rule("bearer", Pattern.compile(
                    "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{12,}")),
            new Rule("authorization", Pattern.compile(
                    "(?i)\\bauthorization\\s*[:=]\\s*\\S+")),
            // Google OAuth access tokens.
            new Rule("googleToken", Pattern.compile("\\bya29\\.[A-Za-z0-9._-]{10,}")),
            // Google API keys.
            new Rule("googleApiKey", Pattern.compile("\\bAIza[A-Za-z0-9_-]{30,}")),
            // Anthropic / OpenAI style keys.
            new Rule("vendorKey", Pattern.compile("\\b(sk|pk)-[A-Za-z0-9_-]{16,}")),
            new Rule("anthropicKey", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{16,}")),
            // AWS.
            new Rule("awsAccessKey", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b")),
            // GitHub.
            new Rule("githubToken", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}")),
            // Slack.
            new Rule("slackToken", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
            // JWTs — three base64url segments.
            new Rule("jwt", Pattern.compile(
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")),
            // Named fields, in JSON, YAML, query strings or prose.
            new Rule("namedSecret", Pattern.compile(
                    "(?i)\\b(private_key|privateKey|client_secret|clientSecret|api[_-]?key|apiKey|"
                            + "access[_-]?token|accessToken|refresh[_-]?token|refreshToken|password|passwd|"
                            + "secret|credential)\\b\\s*[\"']?\\s*[:=]\\s*[\"']?([^\"',;\\s}]{4,})")),
            // Connection strings carrying inline credentials.
            new Rule("uriCredentials", Pattern.compile(
                    "\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@")));

    /**
     * @param text any text bound for an AI tool
     * @return the text with credential-shaped spans masked; null in, null out
     */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = switch (rule.name()) {
                // Keep the field name visible and mask only its value, so the analysis can still reason about
                // "a password was involved" without receiving one.
                case "namedSecret" -> rule.pattern().matcher(result).replaceAll("$1=" + MASK);
                case "uriCredentials" -> rule.pattern().matcher(result).replaceAll(MASK + "@");
                default -> rule.pattern().matcher(result).replaceAll(MASK);
            };
        }
        return result;
    }

    /**
     * @param text any text
     * @return whether scrubbing would change it — used to flag, in the audit record, that something
     *         credential-shaped was present and removed
     */
    public boolean containsSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return !text.equals(scrub(text));
    }
}
