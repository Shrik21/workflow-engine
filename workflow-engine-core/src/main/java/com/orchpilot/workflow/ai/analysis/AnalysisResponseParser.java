package com.orchpilot.workflow.ai.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the model's answer into an {@link ErrorAnalysis}, and checks its IAM claims.
 *
 * <h2>Parsing is forgiving; the claims are not</h2>
 *
 * A model asked for bare JSON will sometimes wrap it in a markdown fence or add a sentence of preamble, so the
 * parser finds the JSON object rather than insisting the whole response is one. That is tolerance about
 * <em>format</em>.
 *
 * <p>There is no equivalent tolerance about <em>content</em>. Every IAM claim goes through
 * {@link GcpIamKnowledge}, and anything that cannot be confirmed is recorded in
 * {@link ErrorAnalysis#warnings()} with {@link ErrorAnalysis#verified()} false. The parser never repairs a
 * claim, never substitutes its own role for a wrong one, and never drops a warning to make the answer look
 * cleaner — a recommendation that is presented as checked when it was not is worse than no recommendation.
 */
@Component
public class AnalysisResponseParser {

    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    private final ObjectMapper mapper = new ObjectMapper();
    private final GcpIamKnowledge iam;

    public AnalysisResponseParser(GcpIamKnowledge iam) {
        this.iam = iam;
    }

    /**
     * @param response   the model's raw answer
     * @param analysedBy the configuration that produced it
     * @return the parsed, validated analysis
     */
    public ErrorAnalysis parse(String response, String analysedBy) {
        String json = extractJsonObject(response);
        if (json == null) {
            return new ErrorAnalysis(false, null, null, null, null,
                    "The AI did not return a JSON object, so its answer could not be used.", null, false,
                    null, false,
                    List.of("The response was not in the requested format."), analysedBy);
        }

        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return new ErrorAnalysis(false, null, null, null, null,
                    "The AI's answer was not valid JSON.", null, false, null, false,
                    List.of("The response could not be parsed."), analysedBy);
        }

        String missingPermission = text(root, "missingPermission");
        String recommendedRole = text(root, "recommendedRole");

        List<String> warnings = new ArrayList<>(iam.validate(missingPermission, recommendedRole));

        // A permission the engine does know about, where the model named no role or an unlisted one: offer the
        // engine's own least-privilege answer alongside, rather than replacing what the model said.
        if (iam.isKnownPermission(missingPermission)) {
            iam.leastPrivilegeRole(missingPermission).ifPresent(known -> {
                if (recommendedRole == null || recommendedRole.isBlank()) {
                    warnings.add("This engine's IAM reference suggests " + known + " as the narrowest role "
                            + "containing " + missingPermission + ".");
                }
            });
        }

        String risk = normaliseRisk(text(root, "securityRisk"), warnings);
        boolean verified = warnings.isEmpty() && missingPermission != null
                && iam.isKnownPermission(missingPermission);

        return new ErrorAnalysis(true,
                text(root, "errorType"),
                missingPermission,
                recommendedRole,
                text(root, "resource"),
                text(root, "reason"),
                risk,
                root.path("canRetry").asBoolean(false),
                text(root, "recommendedAction"),
                verified,
                warnings,
                analysedBy);
    }

    /**
     * Finds the JSON object in a response that may carry a fence or a sentence around it.
     *
     * <p>Scans for the outermost balanced pair of braces, tracking string literals so a brace inside a quoted
     * value does not end the object early.
     */
    static String extractJsonObject(String response) {
        if (response == null) {
            return null;
        }
        String text = response.strip();
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** An unrecognised risk level becomes the cautious one, and the substitution is recorded. */
    private static String normaliseRisk(String risk, List<String> warnings) {
        if (risk == null || risk.isBlank()) {
            return "MEDIUM";
        }
        String upper = risk.trim().toUpperCase(java.util.Locale.ROOT);
        if (RISK_LEVELS.contains(upper)) {
            return upper;
        }
        warnings.add("The AI reported an unrecognised security risk level ('" + risk
                + "'); it is being treated as MEDIUM.");
        return "MEDIUM";
    }

    /** @return the field's text, or null for absent, JSON null, or the literal string "null" */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim()) ? null : value.trim();
    }
}
