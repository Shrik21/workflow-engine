package com.orchpilot.workflow.storage.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Where this deployment (or one tenant of it) physically stores workflow files.
 *
 * <h2>One document per tenant</h2>
 *
 * {@code tenantId} is unique and nullable, which in MongoDB means a single-tenant deployment holds exactly one
 * document with {@code tenantId: null}. That is the same nullable-discriminator approach {@code Workflow} and
 * {@code User} already use, so activating tenancy later adds documents rather than reshaping this one.
 *
 * <h2>Why the path lives here and not in configuration</h2>
 *
 * An administrator has to be able to change it from the console without a redeploy, and a container that is
 * restarted with a different mount must not silently start writing somewhere new. Holding it in the database
 * makes the location auditable — who set it, when — which a YAML property never is.
 */
@Document(collection = "workflowStorageSettings")
public class WorkflowStorageSettings {

    @Id
    private String id;

    /**
     * Owning tenant, or {@code null} in a single-tenant deployment.
     *
     * <p>Unique with a sparse index so the {@code null} row is permitted once and cannot be duplicated by a
     * concurrent first-time save.
     */
    @Indexed(unique = true, sparse = true)
    private String tenantId;

    private StorageType storageType = StorageType.LOCAL;

    /**
     * The canonical, absolute root directory.
     *
     * <p>Stored already resolved through {@code toRealPath}, so the containment check that guards every file
     * operation compares against a path with no symlinks, {@code .} or {@code ..} left in it. Storing the raw
     * user input instead would move that resolution — and the chance to get it wrong — to every read.
     */
    private String basePath;

    /** Turning this off refuses uploads without discarding the configured path. */
    private boolean enabled = true;

    private RetentionPolicy retentionPolicy = RetentionPolicy.NEVER;

    /** Only meaningful for {@link RetentionPolicy#CUSTOM}. */
    private Integer retentionDays;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    /** Guards against two administrators saving different paths at the same time. */
    @Version
    private Long documentVersion;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public StorageType getStorageType() {
        return storageType;
    }

    public void setStorageType(StorageType storageType) {
        this.storageType = storageType;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RetentionPolicy getRetentionPolicy() {
        return retentionPolicy;
    }

    public void setRetentionPolicy(RetentionPolicy retentionPolicy) {
        this.retentionPolicy = retentionPolicy;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    @Override
    public String toString() {
        // The path is operational detail, not a secret, but it is still an absolute filesystem location — kept
        // out of the default rendering so it cannot reach a log through an incidental concatenation.
        return "WorkflowStorageSettings{tenantId=" + tenantId + ", storageType=" + storageType
                + ", enabled=" + enabled + "}";
    }
}
