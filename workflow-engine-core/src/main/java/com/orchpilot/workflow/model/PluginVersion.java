package com.orchpilot.workflow.model;

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
 * <p>The JAR itself is not here. It lives in GridFS and this document holds its id and SHA-256, which
 * keeps the document small, lets MongoDB stream a 40 MB archive without loading it into the driver's
 * document buffer, and gives the loader a way to detect a corrupted or substituted binary.
 */
@Document(collection = "workflow_plugin_versions")
@CompoundIndex(name = "plugin_version_unique", def = "{'pluginId': 1, 'version': 1}", unique = true)
public class PluginVersion {

    /** {@code pluginId:version}. */
    @Id
    private String id;

    @Indexed
    private String pluginId;

    private String version;
    private String name;
    private String description;
    private String pluginType;

    /** Fully qualified {@code WorkflowPlugin} implementation, as discovered or declared. */
    private String mainClass;

    /** Plugin API version the JAR was built against. */
    private int apiVersion;

    private String jarFileName;

    /** GridFS file id of the JAR. */
    private String jarFileId;

    private long jarSizeBytes;

    /** SHA-256 of the stored bytes, verified every time the JAR is staged for loading. */
    private String sha256;

    private boolean signed;

    @Indexed
    private PluginStatus status = PluginStatus.INSTALLED;

    /** Node types this version contributes, denormalised for fast catalogue and validation queries. */
    private List<String> nodeTypes = new ArrayList<>();

    private List<NodeDefinitionRecord> nodeDefinitions = new ArrayList<>();

    private PluginPermissions permissions = new PluginPermissions();

    /** Installation-scoped, non-secret settings passed to the plugin at initialisation. */
    private Map<String, Object> settings = new LinkedHashMap<>();

    /** Declared external dependencies, recorded for audit and operator review. */
    private List<String> dependencies = new ArrayList<>();

    private Instant uploadedAt;
    private String uploadedBy;
    private Instant lastLoadedAt;
    private Instant lastUnloadedAt;

    /** Reason the last load attempt failed, when {@code status} is {@link PluginStatus#FAILED}. */
    private String loadError;

    /**
     * The icon the archive shipped, as a {@code data:} URL, or null when it shipped none.
     *
     * <h2>Stored here rather than in GridFS</h2>
     *
     * A capped 128 KB is small enough to sit on the document, and keeping it here means the icon shares the
     * plugin version's lifecycle exactly: installing a version brings its icon, deleting one takes it away,
     * and two versions of a plugin can carry different artwork without a second store to keep in step.
     *
     * <p>Already a data URL rather than raw bytes, because that is the only form anything consumes it in —
     * the designer puts it straight into an {@code <img src>}. Converting on every catalogue read would be
     * work repeated for no benefit.
     *
     * <p>SVG content has been sanitised at ingest; see {@code SvgSanitizer} for what that does and does not
     * protect against.
     */
    private String iconDataUrl;

    /** The archive entry the icon came from, for diagnostics. Null when there is no icon. */
    private String iconSource;

    public PluginVersion() {
    }

    /**
     * @param pluginId plugin id
     * @param version  plugin version
     * @return the deterministic document id for this coordinate
     */
    public static String idFor(String pluginId, String version) {
        return pluginId + ":" + version;
    }

    /** @return {@code pluginId:version} */
    public String coordinate() {
        return idFor(pluginId, version);
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

    public int getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(int apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getJarFileName() {
        return jarFileName;
    }

    public void setJarFileName(String jarFileName) {
        this.jarFileName = jarFileName;
    }

    public String getJarFileId() {
        return jarFileId;
    }

    public void setJarFileId(String jarFileId) {
        this.jarFileId = jarFileId;
    }

    public long getJarSizeBytes() {
        return jarSizeBytes;
    }

    public void setJarSizeBytes(long jarSizeBytes) {
        this.jarSizeBytes = jarSizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public boolean isSigned() {
        return signed;
    }

    public void setSigned(boolean signed) {
        this.signed = signed;
    }

    public PluginStatus getStatus() {
        return status;
    }

    public void setStatus(PluginStatus status) {
        this.status = status;
    }

    public List<String> getNodeTypes() {
        return nodeTypes;
    }

    public void setNodeTypes(List<String> nodeTypes) {
        this.nodeTypes = nodeTypes == null ? new ArrayList<>() : nodeTypes;
    }

    public List<NodeDefinitionRecord> getNodeDefinitions() {
        return nodeDefinitions;
    }

    public void setNodeDefinitions(List<NodeDefinitionRecord> nodeDefinitions) {
        this.nodeDefinitions = nodeDefinitions == null ? new ArrayList<>() : nodeDefinitions;
    }

    public PluginPermissions getPermissions() {
        return permissions;
    }

    public void setPermissions(PluginPermissions permissions) {
        this.permissions = permissions == null ? new PluginPermissions() : permissions;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings == null ? new LinkedHashMap<>() : settings;
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<String> dependencies) {
        this.dependencies = dependencies == null ? new ArrayList<>() : dependencies;
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

    public Instant getLastLoadedAt() {
        return lastLoadedAt;
    }

    public void setLastLoadedAt(Instant lastLoadedAt) {
        this.lastLoadedAt = lastLoadedAt;
    }

    public Instant getLastUnloadedAt() {
        return lastUnloadedAt;
    }

    public void setLastUnloadedAt(Instant lastUnloadedAt) {
        this.lastUnloadedAt = lastUnloadedAt;
    }

    public String getLoadError() {
        return loadError;
    }

    public void setLoadError(String loadError) {
        this.loadError = loadError;
    }

    public String getIconDataUrl() {
        return iconDataUrl;
    }

    public void setIconDataUrl(String iconDataUrl) {
        this.iconDataUrl = iconDataUrl;
    }

    public String getIconSource() {
        return iconSource;
    }

    public void setIconSource(String iconSource) {
        this.iconSource = iconSource;
    }
}
