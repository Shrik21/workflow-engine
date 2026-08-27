package com.orchpilot.workflow.ai.analysis;

import com.orchpilot.workflow.ai.cli.AiCliConfiguration;
import com.orchpilot.workflow.ai.cli.AiCliConfigurationService;
import com.orchpilot.workflow.ai.cli.AiCliException;
import com.orchpilot.workflow.ai.cli.AiCliType;
import com.orchpilot.workflow.ai.cli.ClaudeCliExecutionService;
import com.orchpilot.workflow.ai.cli.OperatingSystemType;
import com.orchpilot.workflow.model.PluginExecutionRecord;
import com.orchpilot.workflow.repository.PluginExecutionRepository;
import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The analysis pipeline end to end, with the CLI mocked out.
 *
 * <p>The GCP permission error from the specification is the worked example throughout, because it is the case
 * the feature exists for.
 */
class AiErrorAnalysisServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String EXECUTION = "exec-1";
    private static final String NODE = "create-vpc";

    private static final String GCP_ERROR = "Permission denied for creating VPC 'prod-vpc'. The service "
            + "account needs the matching Compute IAM role on the project. Required: compute.networks.create "
            + "for projects/project-b2826f14-ab65-460b-82f/global/networks/prod-vpc";

    private static final String GOOD_ANSWER = """
            {
              "errorType": "GCP_PERMISSION_DENIED",
              "missingPermission": "compute.networks.create",
              "recommendedRole": "roles/compute.networkAdmin",
              "resource": "project",
              "reason": "The service account cannot create networks in this project.",
              "securityRisk": "MEDIUM",
              "canRetry": true,
              "recommendedAction": "Grant roles/compute.networkAdmin on the project."
            }""";

    private PluginExecutionRepository executions;
    private AiCliConfigurationService configurations;
    private ClaudeCliExecutionService cli;
    private AuditService audit;
    private AiErrorAnalysisService service;
    private AiCliConfiguration configuration;

    @BeforeEach
    void setUp() {
        executions = mock(PluginExecutionRepository.class);
        configurations = mock(AiCliConfigurationService.class);
        cli = mock(ClaudeCliExecutionService.class);
        audit = mock(AuditService.class);

        configuration = new AiCliConfiguration();
        configuration.setId("cfg-1");
        configuration.setName("Claude CLI - Windows Development");
        configuration.setType(AiCliType.CLAUDE_CLI);
        configuration.setOperatingSystem(OperatingSystemType.WINDOWS);
        configuration.setTenantId(TENANT);
        when(configurations.requireDefault(TENANT, AiCliType.CLAUDE_CLI)).thenReturn(configuration);

        GcpIamKnowledge iam = new GcpIamKnowledge();
        service = new AiErrorAnalysisService(executions, configurations, cli,
                new AnalysisPromptBuilder(), new AnalysisResponseParser(iam), new SensitiveTextScrubber(),
                audit);
    }

    private static PluginExecutionRecord failure(String errorMessage) {
        PluginExecutionRecord record = new PluginExecutionRecord();
        record.setExecutionId(EXECUTION);
        record.setNodeId(NODE);
        record.setNodeType("GCP_NET_CREATE_VPC");
        record.setWorkflowId("wf-1");
        record.setWorkflowVersion(3);
        record.setPluginId("orchpilot-gcp-network");
        record.setPluginVersion("1.0.0");
        record.setAttempt(1);
        record.setErrorCode("GCP_PERMISSION_DENIED");
        record.setErrorMessage(errorMessage);
        record.setRequest(Map.of("operationId", "gcp.network.create"));
        return record;
    }

    // ------------------------------------------------------------------ the worked example

    @Test
    @DisplayName("analyses a GCP permission failure and verifies the IAM claims")
    void analysesPermissionError() {
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(failure(GCP_ERROR)));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        ErrorAnalysis analysis = service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        assertThat(analysis.success()).isTrue();
        assertThat(analysis.missingPermission()).isEqualTo("compute.networks.create");
        assertThat(analysis.recommendedRole()).isEqualTo("roles/compute.networkAdmin");
        assertThat(analysis.canRetry()).isTrue();
        assertThat(analysis.verified()).isTrue();
        assertThat(analysis.analysedBy()).isEqualTo("Claude CLI - Windows Development");
    }

    @Test
    @DisplayName("the prompt carries the context and asks for JSON")
    void promptCarriesContext() {
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(failure(GCP_ERROR)));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cli).executePrompt(any(AiCliConfiguration.class), prompt.capture(), eq(true), eq("dev"));

        assertThat(prompt.getValue()).contains("orchpilot-gcp-network");
        assertThat(prompt.getValue()).contains("gcp.network.create");
        assertThat(prompt.getValue()).contains("GCP_PERMISSION_DENIED");
        assertThat(prompt.getValue()).contains("compute.networks.create");
        // The error is fenced, so quoted material is visibly distinct from the engine's own instructions.
        assertThat(prompt.getValue()).contains("<<<ERROR");
        assertThat(prompt.getValue()).contains("Never invent an IAM permission");
    }

    // ------------------------------------------------------------------ what must not leave

    @Test
    @DisplayName("a credential in the error message is removed before the prompt is built")
    void scrubsCredentialsFromThePrompt() {
        String leaky = "Auth failed. -----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEF\n"
                + "-----END PRIVATE KEY----- while creating VPC 'prod-vpc'.";
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(failure(leaky)));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cli).executePrompt(any(AiCliConfiguration.class), prompt.capture(), anyBoolean(), anyString());

        assertThat(prompt.getValue()).doesNotContain("MIIEvQIBADANBgkqhkiG9w0BAQEF");
        assertThat(prompt.getValue()).doesNotContain("BEGIN PRIVATE KEY");
        // The rest of the message still reaches the model, or the analysis would be useless.
        assertThat(prompt.getValue()).contains("prod-vpc");
    }

    @Test
    @DisplayName("the plugin's request and response bodies are never sent")
    void doesNotSendRequestBodies() {
        PluginExecutionRecord record = failure(GCP_ERROR);
        record.setRequest(Map.of("operationId", "gcp.network.create",
                "connection", "GCP_Auth", "body", "{\"secretish\":\"do-not-send-me\"}"));
        record.setResponse(Map.of("raw", "also-not-for-sending"));
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION)).thenReturn(List.of(record));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cli).executePrompt(any(AiCliConfiguration.class), prompt.capture(), anyBoolean(), anyString());

        // Only the curated fields travel; the record's bodies are not copied into the context.
        assertThat(prompt.getValue()).doesNotContain("do-not-send-me");
        assertThat(prompt.getValue()).doesNotContain("also-not-for-sending");
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    @DisplayName("an unavailable CLI yields an unsuccessful analysis, not a thrown error")
    void survivesUnavailableCli() {
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(failure(GCP_ERROR)));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenThrow(new AiCliException("AI_CLI_NOT_FOUND", "No file at C:\\missing\\claude.cmd"));

        ErrorAnalysis analysis = service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        // The node's own error is what the user came to see; losing it because the assistant is down would be
        // the wrong trade.
        assertThat(analysis.success()).isFalse();
        assertThat(analysis.reason()).contains("could not be produced");
    }

    @Test
    @DisplayName("a node with no recorded failure is reported clearly")
    void refusesWhenNothingFailed() {
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION)).thenReturn(List.of());

        assertThatThrownBy(() -> service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev"))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_ANALYSIS_NO_FAILURE"));
        verify(cli, never()).executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(),
                anyString());
    }

    @Test
    @DisplayName("uses the newest attempt when a node was retried")
    void usesLatestAttempt() {
        PluginExecutionRecord first = failure("transient failure");
        PluginExecutionRecord second = failure(GCP_ERROR);
        second.setAttempt(2);
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(first, second));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(cli).executePrompt(any(AiCliConfiguration.class), prompt.capture(), anyBoolean(), anyString());
        assertThat(prompt.getValue()).contains("compute.networks.create");
        assertThat(prompt.getValue()).doesNotContain("transient failure");
    }

    // ------------------------------------------------------------------ audit

    @Test
    @DisplayName("audits the analysis with full context and no prompt text")
    void auditsTheAnalysis() {
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(failure(GCP_ERROR)));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("dev"), eq("AI_ERROR_ANALYSIS"), eq("WORKFLOW_EXECUTION"), eq(EXECUTION),
                eq("OK"), details.capture());

        Map<String, Object> recorded = details.getValue();
        assertThat(recorded).containsEntry("tenantId", TENANT)
                .containsEntry("workflowId", "wf-1")
                .containsEntry("nodeId", NODE)
                .containsEntry("pluginId", "orchpilot-gcp-network")
                .containsEntry("aiConfiguration", "Claude CLI - Windows Development")
                .containsEntry("verified", true)
                .containsEntry("missingPermission", "compute.networks.create");
        // The prompt itself is not audited: it would be a second copy of whatever the failing system wrote.
        assertThat(recorded).doesNotContainKey("prompt");
        assertThat(recorded.toString()).doesNotContain("Never invent");
    }

    @Test
    @DisplayName("records that credential-shaped text was removed, so the leak upstream can be found")
    void auditsScrubbing() {
        when(executions.findByExecutionIdOrderByStartTimeAsc(EXECUTION))
                .thenReturn(List.of(failure("token ya29.abcdefghijklmnopqrst failed")));
        when(cli.executePrompt(any(AiCliConfiguration.class), anyString(), anyBoolean(), anyString()))
                .thenReturn(GOOD_ANSWER);

        service.analyseNodeFailure(EXECUTION, NODE, null, TENANT, "dev");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(anyString(), anyString(), anyString(), anyString(), anyString(),
                details.capture());

        assertThat(details.getValue()).containsEntry("sensitiveTextRemoved", true);
        // The removed text is of course not in the record either.
        assertThat(details.getValue().toString()).doesNotContain("ya29.");
    }
}
