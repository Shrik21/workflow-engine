package com.orchpilot.workflow.sdk.context;

import com.orchpilot.workflow.sdk.exception.PluginSecurityException;

import java.util.Optional;

/**
 * The only sanctioned way for a plugin to obtain a credential.
 *
 * <p>Secrets are never present in workflow definitions, node configuration or plugin settings. A
 * workflow references a secret by name; the engine resolves it here, checks it against the secret
 * scopes the plugin declared at upload time, records the access in the audit log, and registers the
 * value for log redaction.
 *
 * <p>Every access outside the plugin's declared scopes fails with {@link PluginSecurityException}.
 *
 * @since 1.0.0
 */
public interface SecretProvider {

    /**
     * @param name secret name, e.g. {@code sendgrid.apiKey}
     * @return the secret value, or empty when no such secret exists
     * @throws PluginSecurityException when {@code name} is outside the plugin's declared scopes
     */
    Optional<String> find(String name);

    /**
     * @param name secret name
     * @return the secret value
     * @throws PluginSecurityException when the secret is missing or out of scope
     */
    default String require(String name) {
        return find(name).orElseThrow(
                () -> new PluginSecurityException("Secret '" + name + "' is not available to this plugin"));
    }
}
