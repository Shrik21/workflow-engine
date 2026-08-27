package com.orchpilot.workflow.forms;

import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the form picker offers, and which version a node ends up pinned to.
 *
 * <p>The interesting case is a published form that somebody is currently editing. Its head is DRAFT while its
 * published snapshot is still perfectly usable, so a picker filtering on {@code status == PUBLISHED} would make
 * it vanish from the dropdown mid-edit and reappear on the next publish.
 */
class AvailableFormsTest {

    private FormDefinitionRepository definitions;
    private FormVersionRepository versions;
    private FormDefinitionService service;

    @BeforeEach
    void setUp() {
        definitions = mock(FormDefinitionRepository.class);
        versions = mock(FormVersionRepository.class);
        service = new FormDefinitionService(definitions, versions, mock(AuditService.class));
    }

    @Test
    @DisplayName("the picker asks for published-and-not-archived, not for status PUBLISHED")
    void picksOnPublishedVersionRatherThanStatus() {
        when(definitions.findByPublishedVersionNotNullAndStatusNotOrderByNameAsc(FormStatus.ARCHIVED))
                .thenReturn(List.of(form("f1", "Employee Approval Form", 3, FormStatus.DRAFT)));

        List<FormDefinition> available = service.available();

        assertEquals(1, available.size());
        assertEquals("Employee Approval Form", available.get(0).getName());
        // The predicate is what this asserts: a DRAFT head with a published version is still selectable,
        // because editing a published form is what puts it in that state.
        verify(definitions).findByPublishedVersionNotNullAndStatusNotOrderByNameAsc(FormStatus.ARCHIVED);
    }

    @Test
    @DisplayName("the summary carries an id, a name and a version, and nothing else")
    void summaryIsSmall() {
        FormDefinition form = form("f1", "Employee Approval Form", 3, FormStatus.PUBLISHED);
        form.setDescription("Employee approval request form");

        FormController.FormSummaryResponse summary = FormController.FormSummaryResponse.from(form);

        assertEquals("f1", summary.id());
        assertEquals("Employee Approval Form", summary.name());
        assertEquals(3, summary.version());
        assertEquals(FormStatus.PUBLISHED, summary.status());
    }

    @Test
    @DisplayName("a node's own formVersion wins over the one in its configuration")
    void nodeFieldBeatsConfiguration() {
        FormNodeBinding binding = new FormNodeBinding(service, new FormValidationService());
        WorkflowNode node = new WorkflowNode("approve", "FORM", "Approve");
        node.setFormVersion(4);

        assertEquals(4, binding.pinnedVersionOf(node, Map.of("formVersion", 2)));
    }

    @Test
    @DisplayName("a workflow authored before the field existed keeps resolving to its configured version")
    void configurationIsStillHonoured() {
        FormNodeBinding binding = new FormNodeBinding(service, new FormValidationService());
        WorkflowNode legacy = new WorkflowNode("approve", "FORM", "Approve");

        assertEquals(2, binding.pinnedVersionOf(legacy, Map.of("formVersion", 2)));
        assertNull(binding.pinnedVersionOf(legacy, Map.of()),
                "with neither set, the node follows the newest published version");
    }

    @Test
    @DisplayName("resolving a node reads the pinned version from the node, not only from the map")
    void resolveUsesTheNodeField() {
        FormVersion pinned = new FormVersion();
        pinned.setFormDefinitionId("f1");
        pinned.setVersion(4);
        when(versions.findByFormDefinitionIdAndVersion("f1", 4)).thenReturn(Optional.of(pinned));

        FormNodeBinding binding = new FormNodeBinding(service, new FormValidationService());
        WorkflowNode node = new WorkflowNode("approve", "FORM", "Approve");
        node.setFormId("f1");
        node.setFormVersion(4);

        assertEquals(4, binding.resolve(node, Map.of()).orElseThrow().getVersion());
        verify(versions).findByFormDefinitionIdAndVersion(eq("f1"), eq(4));
    }

    private static FormDefinition form(String id, String name, Integer publishedVersion, FormStatus status) {
        FormDefinition form = new FormDefinition();
        form.setId(id);
        form.setName(name);
        form.setPublishedVersion(publishedVersion);
        form.setStatus(status);
        return form;
    }
}
