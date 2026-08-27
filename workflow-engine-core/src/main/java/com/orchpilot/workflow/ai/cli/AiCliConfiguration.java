package com.orchpilot.workflow.ai.cli;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A named AI CLI tool the engine host may run.
 *
 * <h2>What is deliberately not here</h2>
 *
 * No credential of any kind. An AI CLI authenticates itself — {@code claude} reads its own stored login from the
 * user profile of the account the engine runs as — so the engine neither needs nor wants a copy. If a provider
 * ever requires a key, it is referenced through {@code secretName} and resolved through the existing secret
 * store; the value never lands in this document, in an API response, or in an audit record.
 *
 * <p>{@code version} and {@code status} are a cache of the last successful check, not authority. They exist so
 * the settings list can render without spawning a process for every row.
 *
 * @see AiCliConfigurationService for the invariant that at most one configuration per tenant is default
 */
@Document(collection = "aiCliConfigurations")
public class AiCliConfiguration {

    @Id
    private String id;

    /** Display name, e.g. "Claude CLI - Windows Development". */
    private String name;

    /**
     * Which CLI this is. A string rather than an enum so a configuration written by a newer build does not
     * become unreadable to an older one; unknown values are simply not executable.
     */
    private String type = AiCliType.CLAUDE_CLI;

    private OperatingSystemType operatingSystem;

    /** Absolute path to the executable. Validated on every write and again before every execution. */
    private String executablePath;

    private boolean enabled = true;

    /** At most one default per tenant; enforced in the service, not by an index, so the swap can be atomic. */
    private boolean defaultConfiguration;

    /**
     * Owning tenant. Indexed because every query is scoped by it — a configuration names an executable on the
     * host, so leaking one across tenants would leak the host's layout.
     */
    @Indexed
    private String tenantId;

    private AiCliStatus status = AiCliStatus.NOT_CONFIGURED;

    /** Version string from the last successful check, e.g. "1.0.60 (Claude Code)". */
    private String version;

    /** When the version and status were last confirmed. */
    private Instant lastCheckedAt;

    /** Why the last check failed, when status is ERROR. Never contains the executable's raw stderr. */
    private String lastError;

    /** Name of a secret, when a CLI needs one. Null for Claude CLI, which authenticates itself. */
    private String secretName;

    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public OperatingSystemType getOperatingSystem() {
        return operatingSystem;
    }

    public void setOperatingSystem(OperatingSystemType operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    public String getExecutablePath() {
        return executablePath;
    }

    public void setExecutablePath(String executablePath) {
        this.executablePath = executablePath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDefaultConfiguration() {
        return defaultConfiguration;
    }

    public void setDefaultConfiguration(boolean defaultConfiguration) {
        this.defaultConfiguration = defaultConfiguration;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public AiCliStatus getStatus() {
        return status;
    }

    public void setStatus(AiCliStatus status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
