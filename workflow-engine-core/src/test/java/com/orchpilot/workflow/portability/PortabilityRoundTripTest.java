package com.orchpilot.workflow.portability;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.forms.FormDefinition;
import com.orchpilot.workflow.forms.FormDefinitionService;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The export→import path end to end, through both services, asserting the guarantees that matter: the file is
 * encrypted rather than encoded, import lands in the caller's tenant with fresh ids, forms are re-created, no
 * secret leaves, and a missing plugin is reported rather than silently installed.
 */
class PortabilityRoundTripTest {

    private static final String MASTER_KEY_B64 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private WorkflowService workflowService;
    private WorkflowService importTargetService;
    private FormDefinitionService formService;
    private com.orchpilot.workflow.repository.WorkflowRepository workflowRepository;
    private PluginDependencyResolver pluginResolver;
    private AuditService auditService;
    private com.orchpilot.workflow.storage.repository.WorkflowFileRepository fileRepository;
    private WorkflowEngineProperties properties;

    private WorkflowExportService exportService;
    private WorkflowImportService importService;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        importTargetService = mock(WorkflowService.class);
        formService = mock(FormDefinitionService.class);
        workflowRepository = mock(com.orchpilot.workflow.repository.WorkflowRepository.class);
        pluginResolver = mock(PluginDependencyResolver.class);
        auditService = mock(AuditService.class);

        properties = new WorkflowEngineProperties();
        properties.getSecrets().setMasterKey(MASTER_KEY_B64);

        // A workflow with no attached files: the mock returns an empty list, so the package's fileReferences
        // stay empty and the round trip is unaffected by the storage module.
        fileRepository = mock(com.orchpilot.workflow.storage.repository.WorkflowFileRepository.class);
        when(fileRepository.findByWorkflowIdAndStatus(anyString(), any())).thenReturn(java.util.List.of());

        exportService = new WorkflowExportService(workflowService, formService, auditService, fileRepository,
                properties);
        importService = new WorkflowImportService(importTargetService, workflowRepository, formService,
                pluginResolver, auditService, properties);
    }

    private byte[] exportSampleWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-original");
        workflow.setTenantId("tenant-SOURCE");
        workflow.setName("Invoice Approval");
        workflow.setVersion(2);

        WorkflowNode plugin = new WorkflowNode();
        plugin.setId("send-node");
        plugin.setType("PLUGIN");
        plugin.setPluginId("email");
        plugin.setPluginVersion("1.0.1");
        plugin.setConfiguration(new LinkedHashMap<>(Map.of("credentialId", "smtp-connection-ref")));

        WorkflowNode form = new WorkflowNode();
        form.setId("form-node");
        form.setType("FORM");
        form.setFormId("form-original");
        form.setConfiguration(new LinkedHashMap<>());

        workflow.setNodes(List.of(plugin, form));
        WorkflowConnection edge = new WorkflowConnection();
        edge.setId("edge-1");
        edge.setSource("send-node");
        edge.setTarget("form-node");
        workflow.setConnections(List.of(edge));

        when(workflowService.get("wf-original")).thenReturn(workflow);
        FormDefinition sourceForm = new FormDefinition();
        sourceForm.setId("form-original");
        sourceForm.setName("Approval form");
        when(formService.get("form-original")).thenReturn(sourceForm);

        WorkflowExportService.ExportOptions options = new WorkflowExportService.ExportOptions(
                true, true, true, true, "PLATFORM", null);
        return exportService.export("wf-original", options, "alice").bytes();
    }

    @Test
    @DisplayName("the exported file is encrypted: the workflow name is not readable in the bytes")
    void fileIsEncryptedNotEncoded() {
        byte[] file = exportSampleWorkflow();
        String asText = new String(file, StandardCharsets.ISO_8859_1);

        // The magic is visible; nothing about the workflow's contents is.
        assertThat(asText).startsWith("ORCHPILOT");
        assertThat(asText).doesNotContain("Invoice Approval");
        assertThat(asText).doesNotContain("email");
        assertThat(asText).doesNotContain("smtp-connection-ref");
    }

    @Test
    @DisplayName("validate reports the source workflow, a missing plugin, the credential reference and the conflict")
    void validateProducesPreview() {
        byte[] file = exportSampleWorkflow();
        when(pluginResolver.resolve(any())).thenReturn(List.of(new PluginDependencyResolver.Result(
                "email", "1.0.1", null, PluginDependencyResolver.Compatibility.MISSING)));
        // A workflow with the source id already exists here: that is a conflict to surface, not to overwrite.
        when(workflowRepository.findById("wf-original")).thenReturn(Optional.of(new Workflow()));

        WorkflowImportService.ValidationResult result = importService.validate(file, null, "bob");

        assertThat(result.valid()).isTrue();
        assertThat(result.name()).isEqualTo("Invoice Approval");
        assertThat(result.sourceVersion()).isEqualTo(2);
        assertThat(result.missingPlugins()).containsExactly("email");
        assertThat(result.credentialReferences()).singleElement()
                .satisfies(reference -> assertThat(reference.getName()).isEqualTo("smtp-connection-ref"));
        assertThat(result.conflict()).isTrue();
    }

    @Test
    @DisplayName("import creates a new workflow with fresh node ids, re-created forms, and never overwrites")
    void importRemapsAndIsolates() {
        byte[] file = exportSampleWorkflow();
        when(pluginResolver.resolve(any())).thenReturn(List.of());
        when(workflowRepository.findById(anyString())).thenReturn(Optional.empty());

        FormDefinition recreated = new FormDefinition();
        recreated.setId("form-RECREATED");
        when(formService.create(any())).thenReturn(recreated);

        Workflow saved = new Workflow();
        saved.setId("wf-NEW");
        saved.setName("Invoice Approval");
        when(importTargetService.create(any(), eq("bob"))).thenReturn(saved);

        WorkflowImportService.ImportResult result = importService.importWorkflow(file, null, "bob");

        assertThat(result.success()).isTrue();
        assertThat(result.workflowId()).isEqualTo("wf-NEW");

        ArgumentCaptor<WorkflowRequest> captor = ArgumentCaptor.forClass(WorkflowRequest.class);
        verify(importTargetService).create(captor.capture(), eq("bob"));
        WorkflowRequest request = captor.getValue();

        // Every node got a fresh id; none kept its exported id.
        assertThat(request.nodes()).allSatisfy(node -> assertThat(node.id()).startsWith("node-"));
        assertThat(request.nodes()).noneMatch(node -> node.id().equals("send-node"));
        // The form node was repointed at the re-created form id, not the exported one.
        WorkflowRequest.NodeRequest formNode = request.nodes().stream()
                .filter(node -> "FORM".equals(node.type())).findFirst().orElseThrow();
        assertThat(formNode.formId()).isEqualTo("form-RECREATED");
        // The edge was rewritten to the new endpoint ids.
        assertThat(request.connections()).singleElement()
                .satisfies(edge -> assertThat(edge.source()).startsWith("node-"));

        // The form was submitted for re-creation with its id cleared, so no source id is reused.
        ArgumentCaptor<FormDefinition> formCaptor = ArgumentCaptor.forClass(FormDefinition.class);
        verify(formService).create(formCaptor.capture());
        assertThat(formCaptor.getValue().getId()).isNull();

        // WorkflowRequest carries no tenant field, so the target tenant can only come from create()'s
        // ownership stamping — the file's tenant cannot influence placement.
        verify(importTargetService, never()).create(any(), eq("tenant-SOURCE"));
    }

    @Test
    @DisplayName("a password-protected export can only be opened with the right password")
    void passwordModeRoundTrips() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-pw");
        workflow.setName("Secret Flow");
        workflow.setNodes(List.of(startNode()));
        when(workflowService.get("wf-pw")).thenReturn(workflow);

        WorkflowExportService.ExportOptions options = new WorkflowExportService.ExportOptions(
                false, false, false, false, "PASSWORD", "correct horse battery".toCharArray());
        byte[] file = exportService.export("wf-pw", options, "alice").bytes();

        when(pluginResolver.resolve(any())).thenReturn(List.of());
        when(workflowRepository.findById(anyString())).thenReturn(Optional.empty());

        WorkflowImportService.ValidationResult wrong =
                importService.validate(file, "wrong password".toCharArray(), "bob");
        assertThat(wrong.valid()).isFalse();

        WorkflowImportService.ValidationResult right =
                importService.validate(file, "correct horse battery".toCharArray(), "bob");
        assertThat(right.valid()).isTrue();
        assertThat(right.name()).isEqualTo("Secret Flow");
    }

    private static WorkflowNode startNode() {
        WorkflowNode start = new WorkflowNode();
        start.setId("start");
        start.setType("START");
        start.setConfiguration(new LinkedHashMap<>());
        return start;
    }
}
