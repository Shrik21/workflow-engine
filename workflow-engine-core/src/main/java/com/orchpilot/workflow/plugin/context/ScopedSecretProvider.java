package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.exception.SecretAccessException;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.SecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Gives a plugin access to exactly the secrets it was granted, and nothing else.
 *
 * <p>Scopes are name prefixes declared by the operator at upload time. A plugin scoped to
 * {@code sendgrid.} can read {@code sendgrid.apiKey} but not {@code stripe.secretKey}. An empty scope list
 * denies everything, so a plugin that was installed without an explicit grant cannot read credentials by
 * accident.
 *
 * <p>Every access, allowed or denied, is audited, and every value handed out is registered with the
 * invocation's {@link SecretRedactor} so it cannot leak into logs or the plugin execution record.
 */
public class ScopedSecretProvider implements SecretProvider {

    private static final Logger log = LoggerFactory.getLogger(ScopedSecretProvider.class);

    private final String pluginId;
    private final List<String> scopes;
    private final SecretService secretService;
    private final AuditService auditService;
    private final SecretRedactor redactor;

    /**
     * @param pluginId      plugin requesting secrets
     * @param scopes        secret name prefixes the plugin may read; empty denies all
     * @param secretService secret store
     * @param auditService  audit sink
     * @param redactor      redactor for the current invocation
     */
    public ScopedSecretProvider(String pluginId, List<String> scopes, SecretService secretService,
                                AuditService auditService, SecretRedactor redactor) {
        this.pluginId = pluginId;
        this.scopes = scopes == null ? List.of() : List.copyOf(scopes);
        this.secretService = secretService;
        this.auditService = auditService;
        this.redactor = redactor;
    }

    @Override
    public Optional<String> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (!inScope(name)) {
            auditService.record(pluginId, "SECRET_READ_DENIED", "SECRET", name, "DENIED",
                    java.util.Map.of("reason", "outside declared scopes", "scopes", scopes));
            throw new PluginSecurityException("Plugin '" + pluginId + "' may not read secret '" + name
                    + "'. Declared secret scopes: " + (scopes.isEmpty() ? "none" : scopes));
        }
        try {
            Optional<String> value = secretService.read(name, pluginId);
            value.ifPresent(redactor::remember);
            if (value.isEmpty()) {
                log.debug("Plugin {} requested secret '{}' which does not exist", pluginId, name);
            }
            return value;
        } catch (SecretAccessException ex) {
            // The secret exists but its own allowlist excludes this plugin. Present it to the plugin as a
            // permission failure rather than an engine error, so the plugin can report it sensibly.
            throw new PluginSecurityException(ex.getMessage());
        }
    }

    /**
     * @param name secret name
     * @return whether any declared scope covers the name
     */
    boolean inScope(String name) {
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            if (name.equals(scope) || name.startsWith(scope)) {
                return true;
            }
        }
        return false;
    }
}
