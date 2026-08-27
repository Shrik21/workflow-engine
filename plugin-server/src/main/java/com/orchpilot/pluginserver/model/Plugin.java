package com.orchpilot.pluginserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A plugin, independent of any particular version of it.
 *
 * <p>The {@code _id} is the plugin id itself. That makes "one plugin, one document" a property of the database
 * rather than a convention the code has to maintain, and it means a concurrent first upload of the same plugin
 * cannot produce two heads.
 *
 * <p>{@code latestVersion} is denormalised here and recomputed whenever a version's state changes. It could be
 * derived by querying versions, and it is not, for one reason: the catalogue is read far more often than versions
 * change, and every workflow service in the estate polls it.
 */
@Document(collection = "plugins")
public class Plugin {

    /** The plugin id, for example {@code sendgrid}. Never contains a version. */
    @Id
    private String pluginId;

    private String name;
    private String description;
    private String vendor;
    private String pluginType;

    /**
     * Highest ACTIVE release version, or null when the plugin has none.
     *
     * <p>Pre-releases are excluded deliberately: something that resolves "latest" should never land on
     * {@code 2.0.0-rc.1} because somebody uploaded a release candidate. A plugin with only pre-releases therefore
     * has no latest version, and a workflow service must name the version it wants.
     */
    private String latestVersion;

    @Indexed
    private PluginStatus status = PluginStatus.ACTIVE;

    private int versionCount;

    private Instant createdAt;
    private String createdBy;
    private Instant updatedAt;
    private String updatedBy;

    /** Guards the read-modify-write in latest-version recomputation against concurrent uploads. */
    @Version
    private Long documentVersion;

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getPluginType() {
        return pluginType;
    }

    public void setPluginType(String pluginType) {
        this.pluginType = pluginType;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    public PluginStatus getStatus() {
        return status;
    }

    public void setStatus(PluginStatus status) {
        this.status = status == null ? PluginStatus.ACTIVE : status;
    }

    public int getVersionCount() {
        return versionCount;
    }

    public void setVersionCount(int versionCount) {
        this.versionCount = versionCount;
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

    /** @return whether this plugin is offered at all, whatever the state of its individual versions */
    public boolean isAvailable() {
        return status == PluginStatus.ACTIVE || status == PluginStatus.DEPRECATED;
    }

    @Override
    public String toString() {
        return "Plugin{" + pluginId + " latest=" + latestVersion + " " + status + "}";
    }
}
