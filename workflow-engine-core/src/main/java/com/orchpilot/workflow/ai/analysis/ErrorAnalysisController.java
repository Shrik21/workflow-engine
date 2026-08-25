package com.orchpilot.workflow.ai.analysis;

import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI analysis of failed workflow nodes.
 *
 * <h2>Read-only, by construction</h2>
 *
 * Every endpoint here returns an explanation. None of them changes a permission, a workflow, an execution or a
 * cloud resource. Analysing a failure is deliberately not on the path that fixes it: a recommendation is
 * produced here, and acting on it is a separate, permissioned, confirmed and audited action elsewhere.
 */
@RestController
@RequestMapping("/api/ai/analysis")
@Tag(name = "AI analysis", description = "AI explanations of failed workflow nodes")
public class ErrorAnalysisController {

    private final AiErrorAnalysisService service;
    private final GcpIamKnowledge iam;

    public ErrorAnalysisController(AiErrorAnalysisService service, GcpIamKnowledge iam) {
        this.service = service;
        this.iam = iam;
    }

    /**
     * Analyses a recorded node failure.
     *
     * @param executionId     the workflow instance
     * @param nodeId          the failed node
     * @param configurationId which AI CLI to use, or absent for the tenant's default
     */
    @PreAuthorize("hasAuthority('AI_ERROR_ANALYSIS')")
    @PostMapping("/executions/{executionId}/nodes/{nodeId}")
    @Operation(summary = "Explain why a node failed",
            description = "Sends a curated, credential-scrubbed summary of the failure to the configured AI "
                    + "CLI and checks any IAM claims in the answer against the engine's own reference. "
                    + "Changes nothing.")
    public ErrorAnalysis analyse(@PathVariable String executionId,
                                 @PathVariable String nodeId,
                                 @RequestParam(required = false) String configurationId) {
        return service.analyseNodeFailure(executionId, nodeId, configurationId, principal().getTenantId(),
                principal().getUsername());
    }

    /**
     * Analyses a failure the caller describes directly.
     *
     * <p>Accepts only the fields on {@link ErrorAnalysisContext} — there is no way to pass arbitrary text
     * through to the CLI, because the prompt is built by the engine from these named fields.
     */
    @PreAuthorize("hasAuthority('AI_ERROR_ANALYSIS')")
    @PostMapping
    @Operation(summary = "Explain a failure described in the request")
    public ErrorAnalysis analyse(@RequestBody AnalysisRequestBody body) {
        ErrorAnalysisContext context = new ErrorAnalysisContext(
                principal().getTenantId(), body.workflowId(), body.workflowVersion(), body.workflowInstanceId(),
                body.nodeId(), body.nodeType(), body.pluginId(), body.pluginVersion(), body.operationId(),
                body.cloudProvider(), body.project(), body.resource(), body.errorCode(), body.errorMessage());
        return service.analyse(context, body.configurationId(), principal().getTenantId(), principal().getUsername());
    }

    /** The direct-analysis body; tenant comes from the caller, never from the request. */
    public record AnalysisRequestBody(String workflowId, Integer workflowVersion, String workflowInstanceId,
                                      String nodeId, String nodeType, String pluginId, String pluginVersion,
                                      String operationId, String cloudProvider, String project,
                                      String resource, String errorCode, String errorMessage,
                                      String configurationId) {
    }

    /**
     * The engine's own IAM reference for one permission, with no AI involved.
     *
     * <p>Useful on its own: when an error already names the missing permission — as GCP's messages usually do —
     * this answers "which role contains it" without running anything.
     */
    @PreAuthorize("hasAuthority('AI_ERROR_ANALYSIS')")
    @GetMapping("/iam/permissions/{permission}")
    @Operation(summary = "Look up which predefined roles contain a GCP IAM permission")
    public IamLookupView lookup(@PathVariable String permission) {
        return new IamLookupView(permission,
                iam.isWellFormedPermission(permission),
                iam.isKnownPermission(permission),
                iam.leastPrivilegeRole(permission).orElse(null),
                iam.rolesContaining(permission));
    }

    /** What the engine knows about one permission. {@code known} false means "not in the reference". */
    public record IamLookupView(String permission, boolean wellFormed, boolean known,
                                String leastPrivilegeRole, List<String> roles) {
    }

    /**
     * The authenticated caller.
     *
     * <p>Read from the security context rather than taken as a parameter, so a request cannot name someone
     * else's tenant — which is what keeps an analysis scoped to executions the caller may see.
     */
    private static AuthPrincipal principal() {
        return CurrentUser.principal().orElseThrow(() ->
                new org.springframework.security.access.AccessDeniedException(
                        "This endpoint requires an authenticated user"));
    }
}
