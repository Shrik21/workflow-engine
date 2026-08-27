package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who breaks if a plugin version goes away.
 *
 * <p>The distinction these tests protect is between a node that pins an exact version and one that resolves through
 * the default. Treating them the same either blocks a removal that is safe, which teaches operators to reach for a
 * force flag, or permits one that is not, which is discovered in production.
 */
class PluginUsageServiceTest {

    private WorkflowRepository workflows;
    private PluginUsageService service;

    @BeforeEach
    void setUp() {
        workflows = mock(WorkflowRepository.class);
        service = new PluginUsageService(workflows);
    }

    private static Workflow published(String id, String name, WorkflowNode... nodes) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setNodes(List.of(nodes));
        return workflow;
    }

    private static WorkflowNode node(String id, String type, String pluginId, String pluginVersion) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setName(id);
        node.setType(type);
        node.setPluginId(pluginId);
        node.setPluginVersion(pluginVersion);
        return node;
    }

    private void given(Workflow... found) {
        when(workflows.findUsingPlugin(eq(WorkflowStatus.PUBLISHED), anyString(), any()))
                .thenReturn(List.of(found));
    }

    @Test
    @DisplayName("a node pinning the exact version is a dependent")
    void pinnedExactVersion() {
        given(published("w1", "Onboarding", node("n1", "PLUGIN", "sendgrid", "1.0.0")));

        List<PluginUsageService.Usage> dependents = service.dependents("sendgrid", "1.0.0",
                List.of("SENDGRID_EMAIL"), false);

        assertEquals(1, dependents.size());
        assertTrue(dependents.get(0).pinned());
        assertEquals("Onboarding", dependents.get(0).workflowName());
    }

    @Test
    @DisplayName("a node pinning a different version is not a dependent")
    void pinnedOtherVersion() {
        given(published("w1", "Onboarding", node("n1", "PLUGIN", "sendgrid", "1.2.0")));

        assertTrue(service.dependents("sendgrid", "1.0.0", List.of("SENDGRID_EMAIL"), false).isEmpty());
        assertFalse(service.isPinnedByPublishedWorkflow("sendgrid", "1.0.0", List.of("SENDGRID_EMAIL")));
    }

    @Test
    @DisplayName("an unpinned node depends on the version it would resolve to, and only that one")
    void unpinnedFollowsTheDefault() {
        given(published("w1", "Onboarding", node("n1", "SENDGRID_EMAIL", null, null)));

        List<PluginUsageService.Usage> whenDefault = service.dependents("sendgrid", "1.0.0",
                List.of("SENDGRID_EMAIL"), true);
        List<PluginUsageService.Usage> whenNotDefault = service.dependents("sendgrid", "1.0.0",
                List.of("SENDGRID_EMAIL"), false);

        assertEquals(1, whenDefault.size());
        assertFalse(whenDefault.get(0).pinned());
        assertTrue(whenNotDefault.isEmpty());
    }

    @Test
    @DisplayName("a node naming the plugin without a version follows the default too")
    void unpinnedByPluginId() {
        given(published("w1", "Onboarding", node("n1", "PLUGIN", "sendgrid", null)));

        assertEquals(1, service.dependents("sendgrid", "1.0.0", List.of(), true).size());
        assertTrue(service.dependents("sendgrid", "1.0.0", List.of(), false).isEmpty());
    }

    @Test
    @DisplayName("unrelated nodes in a matched workflow are ignored")
    void ignoresUnrelatedNodes() {
        given(published("w1", "Onboarding",
                node("start", "START", null, null),
                node("n1", "SLACK_MESSAGE", "slack", "1.0.0"),
                node("n2", "SENDGRID_EMAIL", null, null)));

        List<PluginUsageService.Usage> dependents = service.dependents("slack", "1.0.0",
                List.of("SLACK_MESSAGE"), true);

        assertEquals(1, dependents.size());
        assertEquals("n1", dependents.get(0).nodeId());
    }

    @Test
    @DisplayName("only pinned uses block an update's drain")
    void pinnedBlocksDrain() {
        given(published("w1", "Onboarding", node("n1", "PLUGIN", "slack", "1.0.0")));

        assertTrue(service.isPinnedByPublishedWorkflow("slack", "1.0.0", List.of("SLACK_MESSAGE")));
    }
}
