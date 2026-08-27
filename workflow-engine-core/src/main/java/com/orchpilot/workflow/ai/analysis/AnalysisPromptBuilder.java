package com.orchpilot.workflow.ai.analysis;

import org.springframework.stereotype.Component;

/**
 * Builds the prompt sent to the AI CLI.
 *
 * <h2>The prompt is engine-built and travels on standard input</h2>
 *
 * Every part of it comes from this class or from the curated {@link ErrorAnalysisContext}; none of it is
 * assembled from a request body. The error message is interpolated as data inside a delimited block and the
 * whole prompt is written to the process's standard input, never passed as an argument — so an error message
 * beginning with a dash cannot become a flag, and one containing instructions is visibly quoted material rather
 * than part of the engine's own request.
 *
 * <p>The instruction to answer only from the given error, and to say {@code null} rather than guess, is the
 * prompt-side half of the "do not invent IAM permissions" rule. The other half — checking the answer against
 * {@link GcpIamKnowledge} — is the half that is actually enforced, because a prompt is a request and not a
 * guarantee.
 */
@Component
public class AnalysisPromptBuilder {

    /**
     * @param context the scrubbed facts about the failure
     * @return the prompt text
     */
    public String build(ErrorAnalysisContext context) {
        StringBuilder prompt = new StringBuilder(1024);

        prompt.append("""
                You are analysing a failure from an infrastructure automation platform. Answer only from the \
                information given below. Do not speculate beyond it.

                Return ONLY a JSON object, with no prose before or after it and no markdown fences, using \
                exactly these keys:

                {
                  "errorType": "a stable classification, e.g. GCP_PERMISSION_DENIED",
                  "missingPermission": "the exact IAM permission required, or null if not determinable",
                  "recommendedRole": "the narrowest predefined role containing it, or null",
                  "resource": "what the role should be granted on: project, folder, organization or resource",
                  "reason": "one or two sentences on what caused this",
                  "securityRisk": "LOW, MEDIUM or HIGH - the risk of making the recommended change",
                  "canRetry": true or false,
                  "recommendedAction": "the safest resolution, in one or two sentences"
                }

                Rules:
                - Never invent an IAM permission or role name. If the error does not name one and you are not \
                certain, use null.
                - Prefer the narrowest role that contains the permission. Never recommend roles/owner or \
                roles/editor.
                - Recommend granting a role; never suggest disabling security controls.

                """);

        prompt.append("Failure context:\n");
        append(prompt, "Cloud provider", context.cloudProvider());
        append(prompt, "Plugin", context.pluginId());
        append(prompt, "Operation", context.operationId());
        append(prompt, "Node type", context.nodeType());
        append(prompt, "Project", context.project());
        append(prompt, "Resource", context.resource());
        append(prompt, "Error code", context.errorCode());

        // The message is the one field carrying text from another system, so it is fenced rather than inlined:
        // a reader — human or model — can see exactly where quoted material starts and stops.
        prompt.append("\nError message:\n<<<ERROR\n")
                .append(context.errorMessage() == null ? "(none)" : context.errorMessage())
                .append("\nERROR\n");

        prompt.append("""

                Determine:
                1. What caused the error?
                2. Which IAM permission is missing?
                3. Which predefined role normally contains it?
                4. Which resource or project should receive the role?
                5. What is the safest recommended resolution?
                6. Are there security risks?
                7. Can the operation be retried once the permission is fixed?

                Respond with the JSON object only.
                """);

        return prompt.toString();
    }

    private static void append(StringBuilder prompt, String label, String value) {
        if (value != null && !value.isBlank()) {
            prompt.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }
}
