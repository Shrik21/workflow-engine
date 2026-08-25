package com.orchpilot.workflow.ai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing the model's answer, and refusing to vouch for claims that cannot be checked.
 *
 * <p>The parser is forgiving about format and unforgiving about content — these tests hold that line, because
 * an unverified IAM recommendation presented as verified is the failure this whole layer exists to prevent.
 */
class AnalysisResponseParserTest {

    private final AnalysisResponseParser parser = new AnalysisResponseParser(new GcpIamKnowledge());

    private static final String GOOD = """
            {
              "errorType": "GCP_PERMISSION_DENIED",
              "missingPermission": "compute.networks.create",
              "recommendedRole": "roles/compute.networkAdmin",
              "resource": "project",
              "reason": "The service account cannot create networks in this project.",
              "securityRisk": "MEDIUM",
              "canRetry": true,
              "recommendedAction": "Grant roles/compute.networkAdmin at project level."
            }""";

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("parses a well-formed answer and marks it verified")
    void parsesGoodAnswer() {
        ErrorAnalysis analysis = parser.parse(GOOD, "Claude CLI - Windows");

        assertThat(analysis.success()).isTrue();
        assertThat(analysis.errorType()).isEqualTo("GCP_PERMISSION_DENIED");
        assertThat(analysis.missingPermission()).isEqualTo("compute.networks.create");
        assertThat(analysis.recommendedRole()).isEqualTo("roles/compute.networkAdmin");
        assertThat(analysis.canRetry()).isTrue();
        assertThat(analysis.securityRisk()).isEqualTo("MEDIUM");
        assertThat(analysis.analysedBy()).isEqualTo("Claude CLI - Windows");
        // Both claims are in the engine's own reference, so this one can be vouched for.
        assertThat(analysis.verified()).isTrue();
        assertThat(analysis.warnings()).isEmpty();
    }

    // ------------------------------------------------------------------ tolerant about format

    @Test
    @DisplayName("finds the JSON inside a markdown fence")
    void toleratesMarkdownFence() {
        String fenced = "Here is the analysis:\n\n```json\n" + GOOD + "\n```\n\nHope that helps.";

        ErrorAnalysis analysis = parser.parse(fenced, "cli");

        assertThat(analysis.success()).isTrue();
        assertThat(analysis.missingPermission()).isEqualTo("compute.networks.create");
    }

    @Test
    @DisplayName("does not stop at a brace inside a string value")
    void handlesBracesInsideStrings() {
        String response = """
                {"errorType": "X", "reason": "the template {placeholder} was not resolved", \
                "canRetry": false, "securityRisk": "LOW"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        assertThat(analysis.success()).isTrue();
        assertThat(analysis.reason()).contains("{placeholder}");
    }

    @Test
    @DisplayName("treats a JSON null and the string \"null\" alike")
    void handlesNulls() {
        String response = """
                {"errorType": "UNKNOWN", "missingPermission": null, "recommendedRole": "null", \
                "canRetry": false, "securityRisk": "LOW"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        assertThat(analysis.missingPermission()).isNull();
        // A model that writes the word "null" means the same thing as one that writes the literal.
        assertThat(analysis.recommendedRole()).isNull();
    }

    // ------------------------------------------------------------------ unforgiving about content

    @Test
    @DisplayName("refuses to vouch for an invented permission")
    void flagsInventedPermission() {
        String response = """
                {"errorType": "GCP_PERMISSION_DENIED", "missingPermission": "compute.vpc.magicCreate", \
                "recommendedRole": "roles/compute.networkAdmin", "canRetry": true, "securityRisk": "LOW"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        // Reported, not discarded — it may be right — but never presented as confirmed.
        assertThat(analysis.missingPermission()).isEqualTo("compute.vpc.magicCreate");
        assertThat(analysis.verified()).isFalse();
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("could not be confirmed"));
    }

    @Test
    @DisplayName("flags a permission that is not even shaped like one")
    void flagsMalformedPermission() {
        String response = """
                {"missingPermission": "just create the network", "canRetry": true}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        assertThat(analysis.verified()).isFalse();
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("not shaped like"));
    }

    @Test
    @DisplayName("flags an over-broad role even when the permission is right")
    void flagsOverbroadRole() {
        String response = """
                {"missingPermission": "compute.networks.create", "recommendedRole": "roles/owner", \
                "canRetry": true, "securityRisk": "LOW"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        assertThat(analysis.verified()).isFalse();
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("grants far more"));
    }

    @Test
    @DisplayName("flags a role the reference does not link to the permission, and names the ones it does")
    void flagsMismatchedRole() {
        String response = """
                {"missingPermission": "compute.firewalls.create", \
                "recommendedRole": "roles/compute.networkAdmin", "canRetry": true, "securityRisk": "LOW"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        // Firewalls need securityAdmin, not networkAdmin — a plausible-sounding answer that is wrong.
        assertThat(analysis.verified()).isFalse();
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("roles/compute.securityAdmin"));
    }

    @Test
    @DisplayName("suggests the engine's own role when the model named none")
    void suggestsKnownRole() {
        String response = """
                {"missingPermission": "compute.networks.create", "canRetry": true, "securityRisk": "LOW"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        assertThat(analysis.warnings()).anyMatch(w -> w.contains("roles/compute.networkAdmin"));
    }

    @Test
    @DisplayName("an unrecognised risk level becomes MEDIUM, and says so")
    void normalisesRisk() {
        String response = """
                {"missingPermission": "compute.networks.create", \
                "recommendedRole": "roles/compute.networkAdmin", "securityRisk": "CATASTROPHIC"}""";

        ErrorAnalysis analysis = parser.parse(response, "cli");

        assertThat(analysis.securityRisk()).isEqualTo("MEDIUM");
        assertThat(analysis.warnings()).anyMatch(w -> w.contains("unrecognised security risk"));
        // The substitution is itself a reason not to call the analysis verified.
        assertThat(analysis.verified()).isFalse();
    }

    @Test
    @DisplayName("a missing risk level defaults to MEDIUM without a warning")
    void defaultsRiskQuietly() {
        ErrorAnalysis analysis = parser.parse("""
                {"missingPermission": "compute.networks.create", \
                "recommendedRole": "roles/compute.networkAdmin", "canRetry": true}""", "cli");

        assertThat(analysis.securityRisk()).isEqualTo("MEDIUM");
        assertThat(analysis.verified()).isTrue();
    }

    // ------------------------------------------------------------------ bad input

    @Test
    @DisplayName("prose with no JSON is an unsuccessful analysis, not an exception")
    void handlesNoJson() {
        ErrorAnalysis analysis = parser.parse("I could not determine the cause.", "cli");

        assertThat(analysis.success()).isFalse();
        assertThat(analysis.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("malformed JSON is reported rather than thrown")
    void handlesBrokenJson() {
        ErrorAnalysis analysis = parser.parse("{\"errorType\": \"X\", oops}", "cli");

        assertThat(analysis.success()).isFalse();
        assertThat(analysis.reason()).contains("not valid JSON");
    }

    @Test
    @DisplayName("null input does not blow up")
    void handlesNull() {
        assertThat(parser.parse(null, "cli").success()).isFalse();
    }
}
