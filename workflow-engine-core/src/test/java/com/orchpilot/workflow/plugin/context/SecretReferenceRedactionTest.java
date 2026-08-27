package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.variable.DefaultVariableResolver;
import com.orchpilot.workflow.variable.VariableResolver;
import com.orchpilot.workflow.variable.VariableScope;
import com.orchpilot.workflow.variable.VariableStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A secret resolved through {@code ${SECRET.name}} must not survive into the plugin execution record.
 *
 * <p>The record stores the resolved configuration, which is the whole point of it — you can see what a plugin
 * was actually asked to do. That is also exactly why a secret substituted into that configuration has to be
 * registered with the invocation's redactor: without it, using a secret reference would write the credential
 * to MongoDB in clear, which is a worse outcome than the {@code secretRef} field it was meant to replace.
 *
 * <p>Mirrors the lookup {@code PluginNodeExecutor.secretLookup} builds, so the wiring is exercised rather than
 * assumed.
 */
class SecretReferenceRedactionTest {

    private final DefaultVariableResolver resolver = new DefaultVariableResolver();

    /** The same shape as the executor's: fetch, remember, return. */
    private static VariableResolver.SecretLookup lookup(SecretRedactor redactor, Map<String, String> vault) {
        return name -> {
            Optional<String> value = Optional.ofNullable(vault.get(name));
            value.ifPresent(redactor::remember);
            return value;
        };
    }

    @Test
    @DisplayName("the resolved secret is masked in the recorded configuration")
    void masksResolvedSecret() {
        SecretRedactor redactor = new SecretRedactor();
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.WORKFLOW, Map.of("region", "asia-south1"));

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("apiKey", "${SECRET.stripeKey}", "region", "${region}"),
                store,
                lookup(redactor, Map.of("stripeKey", "sk_live_51H8xYzAbCdEf")));

        assertThat(resolution.configuration()).containsEntry("apiKey", "sk_live_51H8xYzAbCdEf");

        // What actually reaches plugin_executions.
        Map<String, Object> recorded = redactor.redactMap(resolution.configuration());

        assertThat(recorded).containsEntry("apiKey", SecretRedactor.MASK);
        // Ordinary variables are untouched: the record still shows what the node was asked to do.
        assertThat(recorded).containsEntry("region", "asia-south1");
    }

    @Test
    @DisplayName("masks the secret wherever it ended up, including inside a longer string")
    void masksInterpolated() {
        SecretRedactor redactor = new SecretRedactor();

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("url", "https://api.example.com?key=${SECRET.token}"),
                VariableStore.create(),
                lookup(redactor, Map.of("token", "t0ken-abcdef-123456")));

        Map<String, Object> recorded = redactor.redactMap(resolution.configuration());

        assertThat(String.valueOf(recorded.get("url"))).doesNotContain("t0ken-abcdef-123456");
        assertThat(String.valueOf(recorded.get("url"))).contains(SecretRedactor.MASK);
    }

    @Test
    @DisplayName("nothing is remembered when no secret was referenced")
    void remembersNothingWithoutSecrets() {
        SecretRedactor redactor = new SecretRedactor();
        VariableStore store = VariableStore.create();
        store.seed(VariableScope.WORKFLOW, Map.of("project", "acme-prod"));

        resolver.resolveConfigurationReporting(Map.of("project", "${project}"), store,
                lookup(redactor, Map.of("stripeKey", "sk_live_51H8xYzAbCdEf")));

        assertThat(redactor.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a secret shorter than the redactor's floor is NOT masked")
    void shortSecretsAreNotMasked() {
        SecretRedactor redactor = new SecretRedactor();

        var resolution = resolver.resolveConfigurationReporting(
                Map.of("pin", "${SECRET.pin}"), VariableStore.create(),
                lookup(redactor, Map.of("pin", "1234")));

        // Documented, not desirable. The redactor ignores values under six characters because masking every
        // occurrence of a short string would redact unrelated text throughout the record. The consequence is
        // that a very short secret referenced this way IS written to the execution record — so short values
        // belong in a secretRef field, which never travels through configuration at all.
        assertThat(redactor.redactMap(resolution.configuration())).containsEntry("pin", "1234");
    }
}
