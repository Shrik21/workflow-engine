package com.orchpilot.workflow.ai.connection;

import com.orchpilot.workflow.ai.AIException;
import com.orchpilot.workflow.ai.AIModelProvider;
import com.orchpilot.workflow.ai.AIProviderFactory;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.ai.model.AIProviderConfiguration;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.SecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages AI provider connections and resolves them into usable configurations.
 *
 * <h2>The one place a key is written, and the one place it is read</h2>
 *
 * A connection's API key is written to the secret store under a name derived from the connection id, and read
 * back only here, only when a configuration is being assembled for an imminent call. No endpoint returns the
 * key, nothing logs it, and it never reaches a workflow definition — the node stores only the connection id.
 * Resolving is deliberately kept in this service so the security boundary is one method, not scattered.
 */
@Service
public class AIProviderConnectionService {

    private static final Logger log = LoggerFactory.getLogger(AIProviderConnectionService.class);

    private final AIProviderConnectionRepository repository;
    private final SecretService secrets;
    private final AIProviderFactory factory;
    private final AuditService audit;

    public AIProviderConnectionService(AIProviderConnectionRepository repository, SecretService secrets,
                                       AIProviderFactory factory, AuditService audit) {
        this.repository = repository;
        this.secrets = secrets;
        this.factory = factory;
        this.audit = audit;
    }

    /** What a caller supplies to create or update a connection; {@code apiKey} is write-only. */
    public record ConnectionRequest(String name, AIProviderType providerType, String endpoint, String apiKey,
                                    Map<String, Object> settings, Boolean enabled) {
    }

    public List<AIProviderConnection> list() {
        return repository.findAll();
    }

    public AIProviderConnection get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No AI provider connection '" + id + "'"));
    }

    public AIProviderConnection create(ConnectionRequest request, String actor) {
        AIProviderConnection connection = new AIProviderConnection();
        connection.setId(UUID.randomUUID().toString());
        connection.setCreatedAt(Instant.now());
        connection.setCreatedBy(actor);
        apply(connection, request, actor);
        AIProviderConnection saved = repository.save(connection);
        audit.record(actor, "AI_CONNECTION_CREATED", "AI_PROVIDER_CONNECTION", saved.getId(), "OK",
                Map.of("provider", String.valueOf(saved.getProviderType()), "name",
                        String.valueOf(saved.getName())));
        return saved;
    }

    public AIProviderConnection update(String id, ConnectionRequest request, String actor) {
        AIProviderConnection connection = get(id);
        apply(connection, request, actor);
        AIProviderConnection saved = repository.save(connection);
        audit.record(actor, "AI_CONNECTION_UPDATED", "AI_PROVIDER_CONNECTION", saved.getId(), "OK", Map.of());
        return saved;
    }

    public void delete(String id, String actor) {
        AIProviderConnection connection = get(id);
        if (connection.getSecretName() != null) {
            try {
                secrets.delete(connection.getSecretName(), actor);
            } catch (RuntimeException ex) {
                log.warn("Could not delete the secret for AI connection {}: {}", id, ex.getMessage());
            }
        }
        repository.deleteById(id);
        audit.record(actor, "AI_CONNECTION_DELETED", "AI_PROVIDER_CONNECTION", id, "OK", Map.of());
    }

    /** Tests a stored connection by resolving it and asking the provider to validate it. */
    public boolean test(String id) {
        AIProviderConnection connection = get(id);
        return factory.forType(connection.getProviderType()).validateConnection(resolve(connection));
    }

    /** Lists the models a stored connection's provider offers. */
    public List<AIModel> models(String id) {
        AIProviderConnection connection = get(id);
        return factory.forType(connection.getProviderType()).getAvailableModels(resolve(connection));
    }

    /**
     * Resolves a connection id into a configuration with the key read from the secret store — for the node
     * executor. The returned configuration is short-lived and its key must not be logged or stored.
     */
    public AIProviderConfiguration resolveById(String id) {
        return resolve(get(id));
    }

    // ------------------------------------------------------------------- internals

    private void apply(AIProviderConnection connection, ConnectionRequest request, String actor) {
        if (request.name() != null) {
            connection.setName(request.name());
        }
        if (request.providerType() != null) {
            connection.setProviderType(request.providerType());
        }
        connection.setEndpoint(request.endpoint());
        if (request.settings() != null) {
            connection.setSettings(request.settings());
        }
        if (request.enabled() != null) {
            connection.setEnabled(request.enabled());
        }
        connection.setUpdatedAt(Instant.now());

        // Write the key to the secret store only when one was supplied, so an update that leaves the key field
        // blank keeps the existing credential rather than wiping it.
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            String secretName = "ai.connection." + connection.getId();
            secrets.write(secretName, request.apiKey(),
                    "AI provider key for connection " + connection.getName(), List.of(), actor);
            connection.setSecretName(secretName);
        }
    }

    private AIProviderConfiguration resolve(AIProviderConnection connection) {
        if (!connection.isEnabled()) {
            throw new AIException("AI_CONNECTION_DISABLED",
                    "The AI provider connection '" + connection.getName() + "' is disabled.", false);
        }
        String key = connection.getSecretName() == null ? null
                : secrets.read(connection.getSecretName(), null).orElse(null);
        return new AIProviderConfiguration(connection.getProviderType(), connection.getEndpoint(), key,
                connection.getSettings());
    }
}
