package com.orchpilot.workflow.variable;

import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ${SECRET.name}} references.
 *
 * <p>This is the one place the platform's usual rule — a workflow stores secret <em>names</em>, never values —
 * is deliberately relaxed, so most of these tests are about the boundaries that keep the relaxation narrow:
 * that an unqualified name can never reach a secret, that a secret can never be shadowed by a variable, that
 * the value never enters the persisted store, and that a plugin's granted scopes still apply.
 */
class SecretReferenceTest {

    private final DefaultVariableResolver resolver = new DefaultVariableResolver();

    private static VariableStore store(Map<String, Object> workflowVariables) {
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.WORKFLOW, workflowVariables);
        return store;
    }

    /** A secret store holding one entry, denying anything outside the {@code gcp.} prefix. */
    private static VariableResolver.SecretLookup vault(Map<String, String> secrets) {
        return name -> Optional.ofNullable(secrets.get(name));
    }

    // ------------------------------------------------------------------ resolving

    @Test
    @DisplayName("resolves a secret into the configuration")
    void resolvesSecret() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${SECRET.gcpProjectId}"),
                store(Map.of()),
                vault(Map.of("gcpProjectId", "acme-prod")));

        assertThat(resolution.isComplete()).isTrue();
        assertThat(resolution.configuration()).containsEntry("project", "acme-prod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"${SECRET.gcpProjectId}", "${secret.gcpProjectId}", "${Secret.gcpProjectId}",
            "${ SECRET.gcpProjectId }"})
    @DisplayName("accepts the prefix in any case, and tolerates whitespace")
    void acceptsCaseVariants(String reference) {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", reference), store(Map.of()),
                vault(Map.of("gcpProjectId", "acme-prod")));

        assertThat(resolution.configuration()).containsEntry("project", "acme-prod");
    }

    @Test
    @DisplayName("resolves inside a longer string and inside nested structures")
    void resolvesEverywhere() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("url", "https://${SECRET.host}/v1",
                        "headers", List.of(Map.of("value", "${SECRET.token}"))),
                store(Map.of()),
                vault(Map.of("host", "api.internal", "token", "abc123")));

        assertThat(resolution.configuration()).containsEntry("url", "https://api.internal/v1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headers = (List<Map<String, Object>>) resolution.configuration()
                .get("headers");
        assertThat(headers.get(0)).containsEntry("value", "abc123");
    }

    // ------------------------------------------------------------------ the boundaries

    @Test
    @DisplayName("an unqualified name never reaches a secret")
    void unqualifiedNeverReachesSecrets() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${gcpProjectId}"),
                store(Map.of()),
                vault(Map.of("gcpProjectId", "acme-prod")));

        // Without this, creating a secret could silently change what an existing expression resolves to.
        assertThat(resolution.isComplete()).isFalse();
        assertThat(resolution.configuration()).containsEntry("project", "${gcpProjectId}");
    }

    @Test
    @DisplayName("a workflow variable cannot shadow a secret reference")
    void variableCannotShadowSecret() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${SECRET.gcpProjectId}"),
                store(Map.of("gcpProjectId", "not-the-secret")),
                vault(Map.of("gcpProjectId", "acme-prod")));

        assertThat(resolution.configuration()).containsEntry("project", "acme-prod");
    }

    @Test
    @DisplayName("a secret reference does not fall back to a variable when the secret is missing")
    void noFallbackToVariables() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${SECRET.gcpProjectId}"),
                store(Map.of("gcpProjectId", "would-be-wrong")),
                vault(Map.of()));

        // Falling back would mean a deleted secret silently starts using some unrelated variable's value.
        assertThat(resolution.isComplete()).isFalse();
        assertThat(resolution.configuration()).containsEntry("project", "${SECRET.gcpProjectId}");
    }

    @Test
    @DisplayName("secrets are denied entirely when no lookup is supplied")
    void deniedWithoutLookup() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("project", "${SECRET.gcpProjectId}"), store(Map.of()));

        // Built-in nodes resolve through this path and have no plugin scope, so they get nothing.
        assertThat(resolution.isComplete()).isFalse();
        assertThat(resolution.unresolved().get(0).variable()).isEqualTo("SECRET.gcpProjectId");
    }

    @Test
    @DisplayName("an out-of-scope secret propagates the provider's refusal")
    void propagatesScopeRefusal() {
        VariableResolver.SecretLookup scoped = name -> {
            if (!name.startsWith("gcp.")) {
                throw new PluginSecurityException("Plugin 'x' may not read secret '" + name + "'");
            }
            return Optional.of("value");
        };

        assertThatThrownBy(() -> resolver.resolveConfigurationReporting(
                Map.of("key", "${SECRET.stripe.apiKey}"), store(Map.of()), scoped))
                .isInstanceOf(PluginSecurityException.class)
                .hasMessageContaining("stripe.apiKey");
    }

    @Test
    @DisplayName("the value never enters the variable store, so it is never persisted")
    void neverEntersTheStore() {
        VariableStore store = store(Map.of("env", "prod"));

        resolver.resolveConfigurationReporting(
                Map.of("project", "${SECRET.gcpProjectId}"), store,
                vault(Map.of("gcpProjectId", "acme-prod")));

        // snapshot() is what gets written to the execution document on every step.
        assertThat(store.snapshot().toString()).doesNotContain("acme-prod");
        assertThat(store.find("SECRET.gcpProjectId")).isEmpty();
        assertThat(store.find("secret.gcpProjectId")).isEmpty();
        assertThat(store.find("gcpProjectId")).isEmpty();
    }

    @Test
    @DisplayName("a secret is not reachable from an expression")
    void notReachableFromExpressions() {
        VariableStore store = store(Map.of("env", "prod"));

        resolver.resolveConfigurationReporting(
                Map.of("project", "${SECRET.gcpProjectId}"), store,
                vault(Map.of("gcpProjectId", "acme-prod")));

        // Decision nodes evaluate against this root; a secret there would be readable by any condition.
        assertThat(store.expressionRoot().toString()).doesNotContain("acme-prod");
        assertThat(store.expressionRoot()).doesNotContainKey("secret");
        assertThat(store.expressionRoot()).doesNotContainKey("SECRET");
    }

    @Test
    @DisplayName("an escaped reference stays literal and fetches nothing")
    void escapedIsLiteral() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("body", "literally $${SECRET.gcpProjectId}"), store(Map.of()),
                name -> {
                    throw new AssertionError("the secret store must not be consulted for an escaped literal");
                });

        assertThat(resolution.isComplete()).isTrue();
        assertThat(resolution.configuration()).containsEntry("body", "literally ${SECRET.gcpProjectId}");
    }

    @Test
    @DisplayName("'SECRET' alone, or a bare prefix, is not a secret reference")
    void requiresAName() {
        assertThat(VariableResolver.secretReference("SECRET")).isNull();
        assertThat(VariableResolver.secretReference("SECRET.")).isNull();
        assertThat(VariableResolver.secretReference("SECRET.   ")).isNull();
        assertThat(VariableResolver.secretReference("secretive.thing")).isNull();
        assertThat(VariableResolver.secretReference(null)).isNull();

        assertThat(VariableResolver.secretReference("SECRET.gcp.key")).isEqualTo("gcp.key");
    }

    @Test
    @DisplayName("a dotted secret name survives intact")
    void dottedSecretName() {
        var resolution = resolver.resolveConfigurationReporting(
                Map.of("connection", "${SECRET.gcp.prod.serviceAccount}"), store(Map.of()),
                vault(Map.of("gcp.prod.serviceAccount", "{\"type\":\"service_account\"}")));

        // The name is passed through whole rather than split on dots, which is how secrets are actually named.
        assertThat(resolution.configuration())
                .containsEntry("connection", "{\"type\":\"service_account\"}");
    }
}
