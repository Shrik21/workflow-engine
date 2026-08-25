package com.orchpilot.pluginserver.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One immutable version of a plugin.
 *
 * <h2>The id is the duplicate guard</h2>
 *
 * <p>{@code _id} is {@code pluginId:version}. Refusing a duplicate upload is then an insert that fails, not a
 * check followed by a write, so two administrators uploading {@code sendgrid 1.2.0} at the same moment cannot
 * both succeed. The check-then-write version of this looks correct in a code review and loses under concurrency.
 *
 * <h2>What is immutable and what is not</h2>
 *
 * <p>Everything describing the artefact is written once: the checksum, the stored file, the manifest's claims,
 * the node types. Only {@code status} and its timestamps change afterwards. A published version is a thing other
 * services have already downloaded and are running; rewriting its contents would mean two workflow services
 * disagreeing about what {@code sendgrid:1.2.0} is.
 *
 * <h2>requestedPermissions, not grantedPermissions</h2>
 *
 * <p>The name is load-bearing. This is what the plugin asked for in its manifest. What it is allowed to do is
 * decided by an administrator in the workflow service that installs it, and is stored there. A plugin that could
 * publish its own effective permissions would be granting them to itself.
 */
@Document(collection = "plugin_versions")
@CompoundIndex(name = "ix_plugin_precedence",
        def = "{'pluginId': 1, 'order.major': -1, 'order.minor': -1, 'order.patch': -1, 'order.releaseRank': -1}")
@CompoundIndex(name = "ix_plugin_status", def = "{'pluginId': 1, 'status': 1}")
public class PluginVersion {

    /** {@code pluginId:version}. */
    @Id
    private String id;

    @Indexed
    private String pluginId;

    private String version;

    /** Sortable precedence, derived from {@link #version}. */
    private VersionOrder order;

    @Indexed
    private PluginStatus status = PluginStatus.DRAFT;

    // ------------------------------------------------------------------- identity

    private String name;
    private String description;
    private String vendor;
    private String pluginType;

    /** Entry point the workflow service instantiates. Recorded here; never loaded here. */
    private String mainClass;

    private String sdkVersion;
    private String javaVersion;

    /** Engine version range such as {@code >=1.0.0 <2.0.0}, or null when unconstrained. */
    private String engineCompatibility;

    // -------------------------------------------------------------------- artefact

    /** SHA-256 of the stored bytes, in lower-case hex. The workflow service verifies against this. */
    @Indexed
    private String checksum;

    /** GridFS file id. */
    private String fileId;

    private String fileName;
    private long fileSize;

    private boolean signed;
    private String signerSubject;

    // -------------------------------------------------------------------- contents

    private List<PluginNode> nodes = new ArrayList<>();
    private List<PluginDependency> dependencies = new ArrayList<>();
    private Map<String, Object> requestedPermissions = new LinkedHashMap<>();

    // ------------------------------------------------------------------- lifecycle

    private Instant uploadedAt;
    private String uploadedBy;
    private Instant publishedAt;
    private String publishedBy;
    private Instant deprecatedAt;
    private Instant revokedAt;
    private String revocationReason;

    /** @return {@code pluginId:version} */
    public static String idOf(String pluginId, String version) {
        return pluginId + ":" + version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
        this.order = VersionOrder.of(version);
    }

    public VersionOrder getOrder() {
        return order;
    }

    /** Present for the document mapper. {@link #setVersion} keeps it in step. */
    public void setOrder(VersionOrder order) {
        this.order = order;
    }

    public PluginStatus getStatus() {
        return status;
    }

    public void setStatus(PluginStatus status) {
        this.status = status == null ? PluginStatus.DRAFT : status;
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

    public String getMainClass() {
        return mainClass;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public String getSdkVersion() {
        return sdkVersion;
    }

    public void setSdkVersion(String sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    public String getEngineCompatibility() {
        return engineCompatibility;
    }

    public void setEngineCompatibility(String engineCompatibility) {
        this.engineCompatibility = engineCompatibility;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public boolean isSigned() {
        return signed;
    }

    public void setSigned(boolean signed) {
        this.signed = signed;
    }

    public String getSignerSubject() {
        return signerSubject;
    }

    public void setSignerSubject(String signerSubject) {
        this.signerSubject = signerSubject;
    }

    public List<PluginNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<PluginNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : new ArrayList<>(nodes);
    }

    public List<PluginDependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<PluginDependency> dependencies) {
        this.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
    }

    public Map<String, Object> getRequestedPermissions() {
        return requestedPermissions;
    }

    public void setRequestedPermissions(Map<String, Object> requestedPermissions) {
        this.requestedPermissions = requestedPermissions == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(requestedPermissions);
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }

    public Instant getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(Instant deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }

    /** @return {@code pluginId:version} */
    public String coordinate() {
        return PluginVersion.idOf(pluginId, version);
    }

    /** @return the node types this version contributes */
    public List<String> nodeTypes() {
        return nodes.stream().map(PluginNode::nodeType).toList();
    }

    @Override
    public String toString() {
        return "PluginVersion{" + coordinate() + " " + status + "}";
    }
}
