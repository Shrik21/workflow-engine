package com.orchpilot.workflow.ai.analysis;

import com.orchpilot.workflow.ai.cli.AiCliConfiguration;
import com.orchpilot.workflow.ai.cli.AiCliConfigurationService;
import com.orchpilot.workflow.ai.cli.AiCliException;
import com.orchpilot.workflow.ai.cli.AiCliType;
import com.orchpilot.workflow.ai.cli.ClaudeCliExecutionService;
import com.orchpilot.workflow.model.PluginExecutionRecord;
import com.orchpilot.workflow.repository.PluginExecutionRepository;
import com.orchpilot.workflow.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explains a failed plugin node using the configured AI CLI.
 *
 * <h2>Assistive only</h2>
 *
 * This service reads an execution record, asks the AI what went wrong, checks the answer's IAM claims, and
 * returns an explanation. It changes nothing. It does not grant a permission, retry a node, alter a workflow,
 * or influence whether the engine considers the execution failed — the failure has already been recorded by the
 * engine, and this runs afterwards at a user's request.
 *
 * <p>That boundary is deliberate and is the whole of the specification's "no blind AI permission granting"
 * rule. A remediation, if one is ever applied, goes through the platform's own authorization, confirmation and
 * audit path; the AI's role ends at producing a recommendation for a human to accept or reject.
 *
 * <h2>What leaves the engine</h2>
 *
 * Only the fields on {@link ErrorAnalysisContext}, with the error message passed through
 * {@link SensitiveTextScrubber} first. Never the node's configuration, its inputs or outputs, the plugin's
 * request body, or any secret.
 */
@Service
public class AiErrorAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiErrorAnalysisService.class);

    private final PluginExecutionRepository executions;
    private final AiCliConfigurationService configurations;
    private final ClaudeCliExecutionService cli;
    private final AnalysisPromptBuilder prompts;
    private final AnalysisResponseParser parser;
    private final SensitiveTextScrubber scrubber;
    private final AuditService audit;

    public AiErrorAnalysisService(PluginExecutionRepository executions,
                                  AiCliConfigurationService configurations,
                                  ClaudeCliExecutionService cli,
                                  AnalysisPromptBuilder prompts,
                                  AnalysisResponseParser parser,
                                  SensitiveTextScrubber scrubber,
                                  AuditService audit) {
        this.executions = executions;
        this.configurations = configurations;
        this.cli = cli;
        this.prompts = prompts;
        this.parser = parser;
        this.scrubber = scrubber;
        this.audit = audit;
    }

    /**
     * Analyses the failure recorded for one node of one execution.
     *
     * @param executionId     the workflow instance
     * @param nodeId          the failed node
     * @param configurationId which AI CLI to use, or null for the tenant's default
     * @param tenantId        the caller's tenant
     * @param actor           who asked
     * @return the analysis
     * @throws AiCliException when there is no such failure, or no usable AI configuration
     */
    public ErrorAnalysis analyseNodeFailure(String executionId, String nodeId, String configurationId,
                                            String tenantId, String actor) {
        PluginExecutionRecord record = findFailure(executionId, nodeId);
        ErrorAnalysisContext context = contextOf(record, tenantId);
        return analyse(context, configurationId, tenantId, actor);
    }

    /**
     * Analyses a failure described directly, for a caller that already holds the details.
     *
     * @param raw             the failure context; its error message is scrubbed here, not by the caller
     * @param configurationId which AI CLI to use, or null for the tenant's default
     * @param tenantId        the caller's tenant
     * @param actor           who asked
     * @return the analysis
     */
    public ErrorAnalysis analyse(ErrorAnalysisContext raw, String configurationId, String tenantId,
                                 String actor) {
        // Scrubbed here rather than trusting a caller to have done it: this is the last point before the text
        // leaves the engine, so it is the only place the guarantee can actually be made.
        boolean scrubbed = scrubber.containsSensitive(raw.errorMessage());
        ErrorAnalysisContext context = scrubbed
                ? withScrubbedMessage(raw, scrubber.scrub(raw.errorMessage()))
                : raw;

        if (scrubbed) {
            log.warn("Credential-shaped text was removed from an error message before analysis "
                    + "(execution {}, node {}). Whatever produced that message should not have included it.",
                    context.workflowInstanceId(), context.nodeId());
        }

        AiCliConfiguration configuration = configurationId == null || configurationId.isBlank()
                ? configurations.requireDefault(tenantId, AiCliType.CLAUDE_CLI)
                : configurations.get(configurationId, tenantId);

        long startedAt = System.currentTimeMillis();
        String errorCode = null;
        ErrorAnalysis analysis;
        try {
            String response = cli.executePrompt(configuration, prompts.build(context), true, actor);
            analysis = parser.parse(response, configuration.getName());
        } catch (AiCliException ex) {
            errorCode = ex.errorCode();
            // A failed analysis is a result, not an exception: the node's own error is what the user came to
            // see, and losing it because the assistant was unavailable would be the wrong trade.
            analysis = ErrorAnalysis.unavailable("The AI analysis could not be produced: " + ex.getMessage());
        }

        auditAnalysis(actor, context, configuration, analysis, scrubbed, errorCode,
                System.currentTimeMillis() - startedAt);
        return analysis;
    }

    /** @return the failure record for a node, newest attempt first */
    private PluginExecutionRecord findFailure(String executionId, String nodeId) {
        List<PluginExecutionRecord> records = executions.findByExecutionIdOrderByStartTimeAsc(executionId);
        PluginExecutionRecord latest = null;
        for (PluginExecutionRecord record : records) {
            if (!nodeId.equals(record.getNodeId())) {
                continue;
            }
            if (record.getErrorCode() == null && record.getErrorMessage() == null) {
                continue;
            }
            if (latest == null || record.getAttempt() > latest.getAttempt()) {
                latest = record;
            }
        }
        if (latest == null) {
            throw new AiCliException("AI_ANALYSIS_NO_FAILURE",
                    "No recorded failure for node '" + nodeId + "' in execution '" + executionId + "'.");
        }
        return latest;
    }

    /**
     * Builds the analysis context from an execution record.
     *
     * <p>Field by field, deliberately. The record also holds the plugin's request and response bodies; those
     * are not copied, and a future change that widens this method should have to be explicit about it.
     */
    private ErrorAnalysisContext contextOf(PluginExecutionRecord record, String tenantId) {
        String pluginId = record.getPluginId();
        return new ErrorAnalysisContext(
                tenantId,
                record.getWorkflowId(),
                record.getWorkflowVersion(),
                record.getExecutionId(),
                record.getNodeId(),
                record.getNodeType(),
                pluginId,
                record.getPluginVersion(),
                operationOf(record),
                cloudProviderOf(pluginId),
                null,
                null,
                record.getErrorCode(),
                record.getErrorMessage());
    }

    /** The capability, when the plugin recorded one; otherwise the node type, which is the next best thing. */
    private static String operationOf(PluginExecutionRecord record) {
        Object operation = record.getRequest() == null ? null : record.getRequest().get("operationId");
        return operation == null ? record.getNodeType() : String.valueOf(operation);
    }

    private static String cloudProviderOf(String pluginId) {
        if (pluginId == null) {
            return null;
        }
        String id = pluginId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("gcp") || id.contains("google")) {
            return "GCP";
        }
        if (id.contains("aws")) {
            return "AWS";
        }
        if (id.contains("azure")) {
            return "AZURE";
        }
        return null;
    }

    private static ErrorAnalysisContext withScrubbedMessage(ErrorAnalysisContext context, String message) {
        return new ErrorAnalysisContext(context.tenantId(), context.workflowId(), context.workflowVersion(),
                context.workflowInstanceId(), context.nodeId(), context.nodeType(), context.pluginId(),
                context.pluginVersion(), context.operationId(), context.cloudProvider(), context.project(),
                context.resource(), context.errorCode(), message);
    }

    private void auditAnalysis(String actor, ErrorAnalysisContext context, AiCliConfiguration configuration,
                               ErrorAnalysis analysis, boolean scrubbed, String errorCode, long millis) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", context.tenantId());
        details.put("workflowId", context.workflowId());
        details.put("workflowVersion", context.workflowVersion());
        details.put("workflowInstanceId", context.workflowInstanceId());
        details.put("nodeId", context.nodeId());
        details.put("pluginId", context.pluginId());
        details.put("pluginVersion", context.pluginVersion());
        details.put("operationId", context.operationId());
        details.put("errorCode", context.errorCode());
        details.put("aiProvider", configuration.getType());
        details.put("aiConfiguration", configuration.getName());
        details.put("executionTimeMs", millis);
        details.put("analysisSucceeded", analysis.success());
        details.put("verified", analysis.verified());
        details.put("missingPermission", analysis.missingPermission());
        details.put("recommendedRole", analysis.recommendedRole());
        // Recorded because it means something upstream leaked a credential into a message, which is worth
        // finding. The removed text itself is of course not recorded.
        details.put("sensitiveTextRemoved", scrubbed);
        if (errorCode != null) {
            details.put("aiErrorCode", errorCode);
        }
        try {
            audit.record(actor, "AI_ERROR_ANALYSIS", "WORKFLOW_EXECUTION", context.workflowInstanceId(),
                    analysis.success() ? "OK" : "FAILED", details);
        } catch (RuntimeException ex) {
            log.warn("Could not write an AI error-analysis audit record: {}", ex.getMessage());
        }
    }
}
