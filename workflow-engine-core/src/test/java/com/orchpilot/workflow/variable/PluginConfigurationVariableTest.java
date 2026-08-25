package com.orchpilot.workflow.variable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plugin node's configuration resolved exactly the way an execution resolves it.
 *
 * <p>Seeds the store the way {@code DefaultExecutionService} does — workflow variables into
 * {@code WORKFLOW}, request input into {@code INPUT} — and then resolves a configuration shaped like the ones
 * the GCP plugins publish, because that is where the reported failure was seen.
 */
class PluginConfigurationVariableTest {

    private final DefaultVariableResolver resolver = new DefaultVariableResolver();

    /** The seeding {@code DefaultExecutionService.newExecution} performs. */
    private static VariableStore executionStore(Map<String, Object> workflowVariables,
                                                Map<String, Object> input) {
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.INPUT, input);
        store.seed(VariableScope.WORKFLOW, workflowVariables);
        return store;
    }

    @Test
    @DisplayName("an unqualified ${gcpProjectId} resolves from the workflow's declared variables")
    void resolvesUnqualifiedWorkflowVariable() {
        VariableStore store = executionStore(Map.of("gcpProjectId", "acme-prod"), Map.of());

        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("connection", "GCP_Auth");
        configuration.put("project", "${gcpProjectId}");
        configuration.put("vpcName", "prod-vpc");

        Map<String, Object> resolved = resolver.resolveConfiguration(configuration, store);

        assertThat(resolved).containsEntry("project", "acme-prod");
    }

    @Test
    @DisplayName("the scope-qualified form resolves too")
    void resolvesQualified() {
        VariableStore store = executionStore(Map.of("gcpProjectId", "acme-prod"), Map.of());

        Map<String, Object> resolved = resolver.resolveConfiguration(
                Map.of("project", "${workflow.gcpProjectId}"), store);

        assertThat(resolved).containsEntry("project", "acme-prod");
    }

    @Test
    @DisplayName("a secret name may itself come from a variable")
    void resolvesSecretName() {
        VariableStore store = executionStore(Map.of("gcpSecret", "GCP_Auth"), Map.of());

        Map<String, Object> resolved = resolver.resolveConfiguration(
                Map.of("connection", "${gcpSecret}"), store);

        // What the plugin then hands to the secret provider must be the resolved name, not the placeholder.
        assertThat(resolved).containsEntry("connection", "GCP_Auth");
    }

    @Test
    @DisplayName("resolves inside nested structures, as a subnet's secondary ranges are")
    void resolvesNested() {
        VariableStore store = executionStore(
                Map.of("gcpProjectId", "acme-prod", "podRange", "10.20.0.0/16"), Map.of());

        Map<String, Object> configuration = Map.of(
                "project", "${gcpProjectId}",
                "secondaryIpRanges", List.of(Map.of("rangeName", "pods", "ipCidrRange", "${podRange}")));

        Map<String, Object> resolved = resolver.resolveConfiguration(configuration, store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranges = (List<Map<String, Object>>) resolved.get("secondaryIpRanges");
        assertThat(ranges.get(0)).containsEntry("ipCidrRange", "10.20.0.0/16");
    }

    @Test
    @DisplayName("a variable inside a longer string is interpolated")
    void interpolates() {
        VariableStore store = executionStore(Map.of("env", "prod"), Map.of());

        Map<String, Object> resolved = resolver.resolveConfiguration(
                Map.of("vpcName", "${env}-vpc"), store);

        assertThat(resolved).containsEntry("vpcName", "prod-vpc");
    }

    @Test
    @DisplayName("an undefined variable stays literal rather than becoming empty")
    void undefinedStaysLiteral() {
        VariableStore store = executionStore(Map.of("somethingElse", "x"), Map.of());

        Map<String, Object> resolved = resolver.resolveConfiguration(
                Map.of("project", "${gcpProjectId}"), store);

        // Deliberate: an empty project would send the request to the wrong place silently.
        assertThat(resolved).containsEntry("project", "${gcpProjectId}");
    }

    @Test
    @DisplayName("a variable declared with surrounding whitespace in its NAME is still found")
    void toleratesWhitespaceInReference() {
        VariableStore store = executionStore(Map.of("gcpProjectId", "acme-prod"), Map.of());

        Map<String, Object> resolved = resolver.resolveConfiguration(
                Map.of("project", "${ gcpProjectId }"), store);

        assertThat(resolved).containsEntry("project", "acme-prod");
    }

    // ------------------------------------------------------------------ reporting what did not resolve

    @Test
    @DisplayName("reports the field and the variable that could not be resolved")
    void reportsUnresolved() {
        VariableStore store = executionStore(Map.of("somethingElse", "x"), Map.of());

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${gcpProjectId}"), store);

        assertThat(resolution.isComplete()).isFalse();
        assertThat(resolution.unresolved()).hasSize(1);
        assertThat(resolution.unresolved().get(0).field()).isEqualTo("project");
        assertThat(resolution.unresolved().get(0).variable()).isEqualTo("gcpProjectId");
    }

    @Test
    @DisplayName("nothing is reported when everything resolves")
    void reportsNothingWhenComplete() {
        VariableStore store = executionStore(Map.of("gcpProjectId", "acme-prod"), Map.of());

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${gcpProjectId}"), store);

        assertThat(resolution.isComplete()).isTrue();
        assertThat(resolution.configuration()).containsEntry("project", "acme-prod");
    }

    @Test
    @DisplayName("an escaped literal is not reported as unresolved")
    void escapedLiteralIsNotUnresolved() {
        VariableStore store = executionStore(Map.of(), Map.of());

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("body", "echo $${HOME}"), store);

        // This is why reporting happens during resolution: afterwards the escaped literal and a genuine
        // typo are textually identical, so a scan of the result could not tell them apart.
        assertThat(resolution.isComplete()).isTrue();
        assertThat(resolution.configuration()).containsEntry("body", "echo ${HOME}");
    }

    @Test
    @DisplayName("names the nested path, so a bad secondary range is findable")
    void reportsNestedPath() {
        VariableStore store = executionStore(Map.of(), Map.of());

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("secondaryIpRanges",
                        List.of(Map.of("rangeName", "pods", "ipCidrRange", "${podRange}"))),
                store);

        assertThat(resolution.unresolved()).hasSize(1);
        assertThat(resolution.unresolved().get(0).field()).isEqualTo("secondaryIpRanges[0].ipCidrRange");
    }

    @Test
    @DisplayName("reports every unresolved reference, not just the first")
    void reportsAll() {
        VariableStore store = executionStore(Map.of(), Map.of());

        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("connection", "${gcpSecret}");
        configuration.put("project", "${gcpProjectId}");
        configuration.put("vpcName", "prod-vpc");

        var resolution = resolver.resolveConfigurationReporting(configuration, store);

        // One failed run per missing variable would be an unpleasant way to find three of them.
        assertThat(resolution.unresolved()).extracting(
                        com.orchpilot.workflow.variable.VariableResolver.UnresolvedReference::variable)
                .containsExactly("gcpSecret", "gcpProjectId");
    }

    @Test
    @DisplayName("reports a reference embedded in a longer string too")
    void reportsInterpolated() {
        VariableStore store = executionStore(Map.of(), Map.of());

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("vpcName", "${env}-vpc"), store);

        assertThat(resolution.unresolved()).hasSize(1);
        assertThat(resolution.unresolved().get(0).variable()).isEqualTo("env");
    }

    @Test
    @DisplayName("the description reads as something an author can act on")
    void describesItself() {
        VariableStore store = executionStore(Map.of(), Map.of());

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${gcpProjectId}"), store);

        assertThat(resolution.unresolved().get(0).toString())
                .isEqualTo("'project' references ${gcpProjectId}");
    }

    @Test
    @DisplayName("input overrides nothing: workflow scope is searched first")
    void workflowScopeWinsOverInput() {
        VariableStore store = executionStore(
                Map.of("gcpProjectId", "from-workflow"), Map.of("gcpProjectId", "from-input"));

        Map<String, Object> resolved = resolver.resolveConfiguration(
                Map.of("project", "${gcpProjectId}"), store);

        assertThat(resolved).containsEntry("project", "from-workflow");
    }
}
