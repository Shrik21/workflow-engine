package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.event.WorkflowEventPublisher;
import com.orchpilot.workflow.model.PluginPermissions;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.SecretService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a {@link DefaultPluginContext} for a plugin version from its declared permissions.
 *
 * <p>This is where an operator's permission grant becomes an enforced constraint. Nothing else constructs a
 * plugin context, so there is exactly one place to audit when asking "what can a plugin actually reach".
 *
 * <p>The JDK HTTP client is shared across every plugin. That is intentional: connection pooling and DNS
 * caching should be engine-wide, and a per-plugin client would let a plugin with a chatty integration
 * exhaust file descriptors on its own. Plugins never see it directly, only through
 * {@link RestrictedHttpClient}.
 */
@Component
public class PluginContextFactory {

    private static final Logger log = LoggerFactory.getLogger(PluginContextFactory.class);

    private final SecretService secretService;
    private final AuditService auditService;
    private final MongoTemplate mongoTemplate;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowEngineProperties properties;
    private final HttpClient sharedHttpClient;

    public PluginContextFactory(SecretService secretService, AuditService auditService,
                                MongoTemplate mongoTemplate, WorkflowEventPublisher eventPublisher,
                                WorkflowEngineProperties properties) {
        this.secretService = secretService;
        this.auditService = auditService;
        this.mongoTemplate = mongoTemplate;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.sharedHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Redirects are not followed: an allowlisted host could otherwise redirect a plugin to one
                // that is not allowlisted, which would make the allowlist decorative.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param descriptor identity of the loaded version
     * @param metadata   persisted version document holding permissions and settings
     * @param workspace  scratch directory private to this version
     * @return a fully wired context
     */
    public DefaultPluginContext create(PluginDescriptor descriptor, PluginVersion metadata, Path workspace) {
        PluginPermissions permissions = metadata.getPermissions();
        WorkflowEngineProperties.Plugins config = properties.getPlugins();
        String coordinate = descriptor.coordinate();
        SecretRedactor redactor = new SecretRedactor();

        List<String> allowedHosts = new ArrayList<>(permissions.getAllowedHosts());
        if (allowedHosts.isEmpty()) {
            allowedHosts.addAll(config.getDefaultAllowedHosts());
        }
        long maxTimeout = permissions.getMaxHttpTimeoutMillis() == null
                ? config.getHttpMaxTimeoutMillis()
                : Math.min(permissions.getMaxHttpTimeoutMillis(), config.getHttpMaxTimeoutMillis());
        long maxResponse = permissions.getMaxResponseBytes() == null
                ? config.getHttpMaxResponseBytes()
                : Math.min(permissions.getMaxResponseBytes(), config.getHttpMaxResponseBytes());

        log.info("Plugin {} granted hosts={} secretScopes={} dataStore={} events={}", coordinate,
                allowedHosts.isEmpty() ? "none" : allowedHosts,
                permissions.getSecretScopes().isEmpty() ? "none" : permissions.getSecretScopes(),
                permissions.isDataStoreEnabled(), permissions.isEventsEnabled());

        return new DefaultPluginContext(
                descriptor,
                new Slf4jPluginLogger(descriptor.id(), descriptor.version(), redactor),
                new MapPluginSettings(metadata.getSettings()),
                new ScopedSecretProvider(descriptor.id(), permissions.getSecretScopes(), secretService,
                        auditService, redactor),
                new RestrictedHttpClient(sharedHttpClient, coordinate, allowedHosts, maxTimeout, maxResponse),
                new MongoPluginDataStore(descriptor.id(), mongoTemplate, permissions.isDataStoreEnabled(),
                        config.getDataStoreMaxResults()),
                new MongoIdempotencyStore(descriptor.id(), mongoTemplate),
                new CorePluginEventPublisher(coordinate, eventPublisher, permissions.isEventsEnabled()),
                workspace,
                redactor);
    }

    @PreDestroy
    void shutdown() {
        // HttpClient became closeable in Java 21; on 17 it is released with the JVM. Executor threads are
        // daemon threads, so nothing here blocks shutdown.
        log.debug("Plugin context factory shutting down");
    }
}
