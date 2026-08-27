package com.orchpilot.workflow.ai.analysis;

import java.util.List;

/**
 * A structured explanation of why a node failed and what to do about it.
 *
 * <h2>{@code verified} is the important field</h2>
 *
 * Everything else here came from a language model. {@link #verified} says whether the specific IAM claims — the
 * missing permission and the recommended role — were confirmed against the engine's own IAM knowledge, and
 * {@link #warnings} says what could not be confirmed. A UI that renders the recommendation without rendering
 * this distinction would be presenting a guess as a fact, which is the failure mode the specification's "do not
 * allow the AI to invent IAM permissions" is guarding against.
 *
 * @param success            whether the analysis itself succeeded
 * @param errorType          the classified error, e.g. {@code GCP_PERMISSION_DENIED}
 * @param missingPermission  the IAM permission the operation needed, when identified
 * @param recommendedRole    a predefined role containing it, when identified
 * @param resource           what the role should be granted on, e.g. {@code project}
 * @param reason             why the error happened, in plain language
 * @param securityRisk       {@code LOW}, {@code MEDIUM} or {@code HIGH} — the risk of the recommended change
 * @param canRetry           whether the operation is worth retrying once resolved
 * @param recommendedAction  the safest resolution
 * @param verified           whether the IAM claims were confirmed against known metadata
 * @param warnings           what could not be confirmed, or was contradicted
 * @param analysedBy         which AI configuration produced this
 */
public record ErrorAnalysis(boolean success, String errorType, String missingPermission,
                            String recommendedRole, String resource, String reason, String securityRisk,
                            boolean canRetry, String recommendedAction, boolean verified,
                            List<String> warnings, String analysedBy) {

    public ErrorAnalysis {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** An analysis that could not be produced, described in the same shape so the UI has one code path. */
    public static ErrorAnalysis unavailable(String reason) {
        return new ErrorAnalysis(false, null, null, null, null, reason, null, false, null, false,
                List.of(), null);
    }
}
