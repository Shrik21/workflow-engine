package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.exception.SecretAccessException;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.SecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permission and redaction guards applied to plugins.
 *
 * <p>These are the controls that make in-process plugin loading defensible for reviewed code. They are cooperative:
 * they constrain a plugin that goes through the provided APIs, which is exactly what they are documented to do.
 */
class PluginGuardsTest {

    /** Audit sink that records calls so tests can assert that a denial was recorded. */
    private static final class RecordingAudit implements AuditService {

        private final List<String> actions = new java.util.ArrayList<>();

        @Override
        public void record(String actor, String action, String entityType, String entityId, String outcome,
                           Map<String, Object> details) {
            actions.add(action + ":" + outcome);
        }

        @Override
        public org.springframework.data.domain.Page<com.orchpilot.workflow.model.AuditRecord> history(
                String entityType, String entityId, org.springframework.data.domain.Pageable pageable) {
            return org.springframework.data.domain.Page.empty();
        }
    }

    /** Secret store stub. */
    private static final class StubSecrets implements SecretService {

        private final Map<String, String> values;
        private final Map<String, String> restrictedTo;

        private StubSecrets(Map<String, String> values, Map<String, String> restrictedTo) {
            this.values = values;
            this.restrictedTo = restrictedTo;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<String> read(String name, String pluginId) {
            if (!values.containsKey(name)) {
                return Optional.empty();
            }
            String allowed = restrictedTo.get(name);
            if (allowed != null && !allowed.equals(pluginId)) {
                throw new SecretAccessException("Secret '" + name + "' is not readable by '" + pluginId + "'");
            }
            return Optional.of(values.get(name));
        }

        @Override
        public void write(String name, String value, String description, List<String> allowedPlugins,
                          String actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(String name, String actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SecretSummary> list() {
            return List.of();
        }
    }

    // ------------------------------------------------------------ host allowlist

    @Test
    @DisplayName("exact hosts, wildcards and 'any' are matched; unrelated hosts are not")
    void hostPatternsMatchAsDocumented() {
        assertTrue(RestrictedHttpClient.matches("api.sendgrid.com", "api.sendgrid.com"));
        assertTrue(RestrictedHttpClient.matches("api.sendgrid.com", "*.sendgrid.com"));
        assertTrue(RestrictedHttpClient.matches("sendgrid.com", "*.sendgrid.com"));
        assertTrue(RestrictedHttpClient.matches("anything.example.org", "*"));

        assertFalse(RestrictedHttpClient.matches("api.sendgrid.com.evil.test", "api.sendgrid.com"));
        assertFalse(RestrictedHttpClient.matches("evil.test", "*.sendgrid.com"));
        assertFalse(RestrictedHttpClient.matches("api.sendgrid.com", null));
        assertFalse(RestrictedHttpClient.matches("api.sendgrid.com", "  "));
    }

    @Test
    @DisplayName("a plugin with no granted hosts cannot reach the network at all")
    void emptyAllowlistDeniesEverything() {
        RestrictedHttpClient client = new RestrictedHttpClient(HttpClient.newHttpClient(), "test:1.0.0",
                List.of(), 1_000, 1_024);

        PluginSecurityException ex = assertThrows(PluginSecurityException.class,
                () -> client.execute(HttpRequestSpec.get("https://api.sendgrid.com/v3/mail/send").build()));
        assertTrue(ex.getMessage().contains("no allowed hosts"), ex.getMessage());
    }

    @Test
    @DisplayName("a host outside the allowlist is refused")
    void hostOutsideAllowlistIsRefused() {
        RestrictedHttpClient client = new RestrictedHttpClient(HttpClient.newHttpClient(), "test:1.0.0",
                List.of("api.sendgrid.com"), 1_000, 1_024);

        assertThrows(PluginSecurityException.class,
                () -> client.execute(HttpRequestSpec.get("https://evil.test/steal").build()));
    }

    @Test
    @DisplayName("non-HTTP schemes are refused, so an HTTP client cannot be used to read files")
    void nonHttpSchemesAreRefused() {
        RestrictedHttpClient client = new RestrictedHttpClient(HttpClient.newHttpClient(), "test:1.0.0",
                List.of("*"), 1_000, 1_024);

        assertThrows(PluginSecurityException.class,
                () -> client.execute(HttpRequestSpec.get("file://localhost/etc/passwd").build()));
    }

    // ---------------------------------------------------------------- secrets

    @Test
    @DisplayName("a secret inside a declared scope is readable")
    void inScopeSecretIsReadable() {
        ScopedSecretProvider provider = new ScopedSecretProvider("sendgrid", List.of("sendgrid."),
                new StubSecrets(Map.of("sendgrid.apiKey", "SG.secret-value"), Map.of()),
                new RecordingAudit(), new SecretRedactor());

        assertEquals("SG.secret-value", provider.require("sendgrid.apiKey"));
    }

    @Test
    @DisplayName("a secret outside the declared scopes is refused and the denial is audited")
    void outOfScopeSecretIsRefused() {
        RecordingAudit audit = new RecordingAudit();
        ScopedSecretProvider provider = new ScopedSecretProvider("sendgrid", List.of("sendgrid."),
                new StubSecrets(Map.of("stripe.secretKey", "sk_live_x"), Map.of()), audit,
                new SecretRedactor());

        assertThrows(PluginSecurityException.class, () -> provider.find("stripe.secretKey"));
        assertTrue(audit.actions.contains("SECRET_READ_DENIED:DENIED"));
    }

    @Test
    @DisplayName("no declared scopes means no secret access at all")
    void emptyScopesDenyEverything() {
        ScopedSecretProvider provider = new ScopedSecretProvider("plain", List.of(),
                new StubSecrets(Map.of("any.secret", "v"), Map.of()), new RecordingAudit(),
                new SecretRedactor());

        assertFalse(provider.inScope("any.secret"));
        assertThrows(PluginSecurityException.class, () -> provider.find("any.secret"));
    }

    @Test
    @DisplayName("a secret's own plugin allowlist is enforced even when the scope matches")
    void secretOwnAllowlistIsEnforced() {
        ScopedSecretProvider provider = new ScopedSecretProvider("slack", List.of("shared."),
                new StubSecrets(Map.of("shared.token", "v"), Map.of("shared.token", "sendgrid")),
                new RecordingAudit(), new SecretRedactor());

        assertThrows(PluginSecurityException.class, () -> provider.find("shared.token"),
                "both the plugin's scope and the secret's allowlist must agree");
    }

    @Test
    @DisplayName("a secret that does not exist is empty rather than an error")
    void missingSecretIsEmpty() {
        ScopedSecretProvider provider = new ScopedSecretProvider("echo", List.of("echo."),
                new StubSecrets(Map.of(), Map.of()), new RecordingAudit(), new SecretRedactor());

        assertTrue(provider.find("echo.absent").isEmpty());
        assertThrows(PluginSecurityException.class, () -> provider.require("echo.absent"));
    }

    // --------------------------------------------------------------- redaction

    @Test
    @DisplayName("a secret read by a plugin is stripped from anything about to be persisted")
    void readSecretsAreRedacted() {
        SecretRedactor redactor = new SecretRedactor();
        ScopedSecretProvider provider = new ScopedSecretProvider("sendgrid", List.of("sendgrid."),
                new StubSecrets(Map.of("sendgrid.apiKey", "SG.super-secret-key"), Map.of()),
                new RecordingAudit(), redactor);

        provider.require("sendgrid.apiKey");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("headers", Map.of("Authorization", "Bearer SG.super-secret-key"));
        request.put("body", "plain body");

        Map<String, Object> redacted = redactor.redactMap(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) redacted.get("headers");
        assertEquals("Bearer " + SecretRedactor.MASK, headers.get("Authorization"));
        assertEquals("plain body", redacted.get("body"));
    }

    @Test
    @DisplayName("redaction reaches into nested maps and lists")
    void redactionIsRecursive() {
        SecretRedactor redactor = new SecretRedactor();
        redactor.remember("top-secret-token");

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("list", List.of("prefix top-secret-token suffix", 42));
        value.put("nested", Map.of("deep", Map.of("k", "top-secret-token")));

        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) redactor.redactValue(value);

        assertTrue(String.valueOf(redacted).contains(SecretRedactor.MASK));
        assertFalse(String.valueOf(redacted).contains("top-secret-token"));
    }

    @Test
    @DisplayName("very short values are not tracked, so redaction does not blank out unrelated text")
    void shortValuesAreNotTracked() {
        SecretRedactor redactor = new SecretRedactor();
        redactor.remember("ab");

        assertTrue(redactor.isEmpty());
        assertEquals("ab is a common substring", redactor.redact("ab is a common substring"));
    }
}
