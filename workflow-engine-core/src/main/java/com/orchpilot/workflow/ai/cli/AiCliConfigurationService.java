package com.orchpilot.workflow.ai.cli;

import com.orchpilot.workflow.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates, reads, updates and deletes AI CLI configurations.
 *
 * <h2>Tenant scoping is the security boundary, not a filter</h2>
 *
 * A configuration names a path on the engine host. Returning one to the wrong tenant discloses how that host is
 * laid out and which accounts exist on it, so every read re-checks the tenant after loading by id rather than
 * trusting that an id was only obtainable legitimately.
 *
 * <h2>One default per tenant</h2>
 *
 * Enforced here rather than by a unique index, so that promoting a new default and demoting the old one happen
 * together — a partial index would reject the new default until the old one had been cleared, which is a state
 * an operator would have to know to work around.
 */
@Service
public class AiCliConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(AiCliConfigurationService.class);

    private final AiCliConfigurationRepository repository;
    private final ExecutablePathValidator pathValidator;
    private final AiCliProviderRegistry providers;
    private final AuditService audit;

    public AiCliConfigurationService(AiCliConfigurationRepository repository,
                                     ExecutablePathValidator pathValidator,
                                     AiCliProviderRegistry providers,
                                     AuditService audit) {
        this.repository = repository;
        this.pathValidator = pathValidator;
        this.providers = providers;
        this.audit = audit;
    }

    /** What a caller supplies to create or update a configuration. */
    public record ConfigurationRequest(String name, String type, OperatingSystemType operatingSystem,
                                       String executablePath, Boolean enabled, Boolean defaultConfiguration,
                                       String secretName) {
    }

    public List<AiCliConfiguration> list(String tenantId) {
        return repository.findByTenantIdOrderByNameAsc(tenantId);
    }

    /**
     * @throws AiCliException when there is no such configuration <em>for this tenant</em> — the same message
     *         either way, so a probe cannot distinguish "does not exist" from "belongs to someone else"
     */
    public AiCliConfiguration get(String id, String tenantId) {
        return repository.findById(id)
                // Objects.equals, not tenantId.equals: a single-tenant deployment has a null tenant on both
                // the principal and the document, and requiring non-null would refuse every read there.
                .filter(configuration -> java.util.Objects.equals(tenantId, configuration.getTenantId()))
                .orElseThrow(() -> new AiCliException("AI_CLI_NOT_CONFIGURED",
                        "No AI CLI configuration '" + id + "'."));
    }

    /**
     * The configuration to use when a caller names none.
     *
     * @return the tenant's default, or its only enabled configuration of that type when there is exactly one
     * @throws AiCliException when there is nothing usable, with a message that says what to do about it
     */
    public AiCliConfiguration requireDefault(String tenantId, String type) {
        List<AiCliConfiguration> defaults = repository.findByTenantIdAndDefaultConfigurationIsTrue(tenantId);
        for (AiCliConfiguration configuration : defaults) {
            if (type.equals(configuration.getType()) && configuration.isEnabled()) {
                return configuration;
            }
        }
        List<AiCliConfiguration> enabled =
                repository.findByTenantIdAndTypeAndEnabledIsTrue(tenantId, type);
        if (enabled.size() == 1) {
            return enabled.get(0);
        }
        if (enabled.isEmpty()) {
            throw new AiCliException("AI_CLI_NOT_CONFIGURED",
                    "No enabled " + providers.displayName(type) + " configuration for this tenant. Add one "
                            + "under Settings → AI Configuration.");
        }
        throw new AiCliException("AI_CLI_NO_DEFAULT",
                "Several " + providers.displayName(type) + " configurations are enabled and none is marked as "
                        + "the default. Mark one as default, or name the one to use.");
    }

    public AiCliConfiguration create(ConfigurationRequest request, String tenantId, String actor) {
        AiCliConfiguration configuration = new AiCliConfiguration();
        configuration.setId(UUID.randomUUID().toString());
        configuration.setTenantId(tenantId);
        configuration.setCreatedAt(Instant.now());
        configuration.setCreatedBy(actor);
        apply(configuration, request, tenantId);

        repository.findByTenantIdAndName(tenantId, configuration.getName()).ifPresent(existing -> {
            throw new AiCliException("AI_CLI_NAME_TAKEN",
                    "A configuration named '" + configuration.getName() + "' already exists.");
        });

        AiCliConfiguration saved = repository.save(configuration);
        applyDefaultExclusivity(saved);
        audit(actor, "AI_CLI_CONFIG_CREATED", saved, "OK", null);
        return saved;
    }

    public AiCliConfiguration update(String id, ConfigurationRequest request, String tenantId, String actor) {
        AiCliConfiguration configuration = get(id, tenantId);
        String previousPath = configuration.getExecutablePath();

        apply(configuration, request, tenantId);
        configuration.setUpdatedAt(Instant.now());
        configuration.setUpdatedBy(actor);

        // A changed path invalidates a cached CONNECTED status: it describes the old binary, and showing it
        // against the new one would claim a check that never happened.
        if (previousPath != null && !previousPath.equals(configuration.getExecutablePath())) {
            configuration.setStatus(AiCliStatus.NOT_CONFIGURED);
            configuration.setVersion(null);
            configuration.setLastCheckedAt(null);
            configuration.setLastError(null);
        }

        AiCliConfiguration saved = repository.save(configuration);
        applyDefaultExclusivity(saved);
        audit(actor, "AI_CLI_CONFIG_UPDATED", saved, "OK",
                previousPath != null && !previousPath.equals(saved.getExecutablePath())
                        ? Map.of("pathChanged", true) : null);
        return saved;
    }

    public void delete(String id, String tenantId, String actor) {
        AiCliConfiguration configuration = get(id, tenantId);
        repository.deleteById(id);
        audit(actor, "AI_CLI_CONFIG_DELETED", configuration, "OK", null);
    }

    /** Records the outcome of a test, so the list can show a status without running anything. */
    public void recordCheck(AiCliConfiguration configuration, AiCliTestResult result) {
        configuration.setStatus(result.success() ? AiCliStatus.CONNECTED : AiCliStatus.ERROR);
        configuration.setVersion(result.success() ? result.version() : configuration.getVersion());
        configuration.setLastCheckedAt(Instant.now());
        configuration.setLastError(result.success() ? null : result.message());
        repository.save(configuration);
    }

    // ------------------------------------------------------------------ internals

    private void apply(AiCliConfiguration configuration, ConfigurationRequest request, String tenantId) {
        if (request.name() == null || request.name().isBlank()) {
            throw new AiCliException("AI_CLI_NAME_REQUIRED", "A configuration name is required.");
        }
        String type = request.type() == null || request.type().isBlank()
                ? AiCliType.CLAUDE_CLI : request.type().trim();
        if (!providers.supports(type)) {
            throw new AiCliException("AI_CLI_TYPE_NOT_SUPPORTED",
                    "No provider is installed for AI CLI type '" + type + "'.");
        }
        if (request.operatingSystem() == null) {
            throw new AiCliException("AI_CLI_OS_REQUIRED", "An operating system is required.");
        }

        // Shape only: a configuration may legitimately be prepared for a host this engine is not running on.
        // Whether the file is actually there is checked when it is about to be executed.
        pathValidator.validateShape(request.executablePath(), request.operatingSystem());

        configuration.setName(request.name().trim());
        configuration.setType(type);
        configuration.setOperatingSystem(request.operatingSystem());
        configuration.setExecutablePath(request.executablePath().trim());
        configuration.setTenantId(tenantId);
        if (request.enabled() != null) {
            configuration.setEnabled(request.enabled());
        }
        if (request.defaultConfiguration() != null) {
            configuration.setDefaultConfiguration(request.defaultConfiguration());
        }
        configuration.setSecretName(blankToNull(request.secretName()));
    }

    /** Demotes every other default for the tenant, so exactly one survives. */
    private void applyDefaultExclusivity(AiCliConfiguration saved) {
        if (!saved.isDefaultConfiguration()) {
            return;
        }
        List<AiCliConfiguration> demoted = new ArrayList<>();
        for (AiCliConfiguration other : repository.findByTenantIdAndDefaultConfigurationIsTrue(
                saved.getTenantId())) {
            if (!other.getId().equals(saved.getId())) {
                other.setDefaultConfiguration(false);
                demoted.add(other);
            }
        }
        if (!demoted.isEmpty()) {
            repository.saveAll(demoted);
            log.debug("Demoted {} AI CLI configuration(s) after {} became the default",
                    demoted.size(), saved.getId());
        }
    }

    private void audit(String actor, String action, AiCliConfiguration configuration, String outcome,
                       Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", configuration.getName());
        details.put("type", configuration.getType());
        details.put("operatingSystem", String.valueOf(configuration.getOperatingSystem()));
        // The path is recorded deliberately: which executable the engine was pointed at is the single most
        // security-relevant fact about this feature, and an audit trail that omits it answers nothing.
        details.put("executablePath", configuration.getExecutablePath());
        details.put("enabled", configuration.isEnabled());
        details.put("default", configuration.isDefaultConfiguration());
        details.put("tenantId", configuration.getTenantId());
        if (extra != null) {
            details.putAll(extra);
        }
        audit.record(actor, action, "AI_CLI_CONFIGURATION", configuration.getId(), outcome, details);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
