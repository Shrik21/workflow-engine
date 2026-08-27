package com.orchpilot.workflow.pluginserver;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What this engine has installed, per plugin.
 *
 * <h2>Several versions, on purpose</h2>
 *
 * <p>The versions are a list rather than a single {@code installedVersion} field, because a workflow published
 * against {@code sendgrid:1.0.0} must keep running that exact version after somebody installs 1.2.0 for a new
 * workflow. Reproducibility of a published workflow is the reason this platform pins plugin versions at all, and a
 * single-version installation record would make it impossible to honour.
 *
 * <h2>What lives here and not in the registry</h2>
 *
 * <p>Granted permissions and operator settings. The registry records what a plugin <em>requested</em> in its
 * manifest; an administrator here decides what it is allowed to do and how it is configured. Keeping those on this
 * side is what stops a plugin author from granting themselves anything by editing their own manifest.
 */
@Document(collection = "installed_plugins")
public class InstalledPlugin {

    /** The plugin id. One document per plugin, whatever the number of installed versions. */
    @Id
    private String pluginId;

    /** Denormalised for display, refreshed on each sync. */
    private String name;

    /**
     * Which version serves nodes that name the plugin without pinning a version.
     *
     * <p>Set explicitly by an administrator. Installing a newer version does not move it: a workflow that did not
     * pin a version is already taking a risk, and silently changing what it runs would compound it.
     */
    private String defaultVersion;

    private List<InstalledVersion> versions = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long documentVersion;

    /**
     * One installed version.
     *
     * @param version            the semantic version
     * @param state              where this version is in its local lifecycle
     * @param checksum           SHA-256 verified at install, re-verified when loading from cache
     * @param cachePath          where the archive sits on this node, relative to the cache directory
     * @param fileSize           archive size
     * @param sdkVersion         SDK it declares, kept so compatibility can be re-evaluated offline
     * @param nodeTypes          node types it contributes
     * @param grantedPermissions what an administrator allowed it to do, which is not what it asked for
     * @param settings           operator configuration for this version
     * @param installedAt        when it was installed
     * @param installedBy        who installed it
     * @param lastLoadedAt       when it was last loaded into a class loader
     * @param failure            why the last install or load failed, or null
     */
    public record InstalledVersion(
            String version,
            InstallState state,
            String checksum,
            String cachePath,
            long fileSize,
            String sdkVersion,
            List<String> nodeTypes,
            Map<String, Object> grantedPermissions,
            Map<String, Object> settings,
            Instant installedAt,
            String installedBy,
            Instant lastLoadedAt,
            String failure) {

        public InstalledVersion {
            nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
            grantedPermissions = grantedPermissions == null ? Map.of() : Map.copyOf(grantedPermissions);
            settings = settings == null ? Map.of() : Map.copyOf(settings);
            state = state == null ? InstallState.INSTALLED : state;
        }

        /** @return whether this version is loadable and usable */
        public boolean isUsable() {
            return state == InstallState.ACTIVE || state == InstallState.INSTALLED;
        }

        public InstalledVersion withState(InstallState newState) {
            return new InstalledVersion(version, newState, checksum, cachePath, fileSize, sdkVersion,
                    nodeTypes, grantedPermissions, settings, installedAt, installedBy, lastLoadedAt, failure);
        }

        public InstalledVersion withFailure(String reason) {
            return new InstalledVersion(version, InstallState.INSTALL_FAILED, checksum, cachePath, fileSize,
                    sdkVersion, nodeTypes, grantedPermissions, settings, installedAt, installedBy,
                    lastLoadedAt, reason);
        }
    }

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

    public String getDefaultVersion() {
        return defaultVersion;
    }

    public void setDefaultVersion(String defaultVersion) {
        this.defaultVersion = defaultVersion;
    }

    public List<InstalledVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<InstalledVersion> versions) {
        this.versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    /**
     * @param version the version to look for
     * @return its record, or empty when this engine does not have it
     */
    public Optional<InstalledVersion> version(String version) {
        return versions.stream().filter(candidate -> candidate.version().equals(version)).findFirst();
    }

    /** @return the newest usable version by precedence, which is what "installed" means for a status display */
    public Optional<String> newestUsableVersion() {
        return versions.stream()
                .filter(InstalledVersion::isUsable)
                .map(InstalledVersion::version)
                .max(java.util.Comparator.comparing(
                        com.orchpilot.workflow.sdk.version.SemanticVersion::parse));
    }

    /** @return whether anything about this plugin is currently usable */
    public boolean hasUsableVersion() {
        return versions.stream().anyMatch(InstalledVersion::isUsable);
    }

    /**
     * Replaces or adds a version, leaving the others alone.
     *
     * @param replacement the version record to store
     */
    public void put(InstalledVersion replacement) {
        List<InstalledVersion> next = new ArrayList<>(versions.size() + 1);
        boolean replaced = false;
        for (InstalledVersion existing : versions) {
            if (existing.version().equals(replacement.version())) {
                next.add(replacement);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(replacement);
        }
        this.versions = next;
        this.updatedAt = Instant.now();
    }

    /**
     * @param version version to remove
     * @return whether it was there
     */
    public boolean remove(String version) {
        boolean removed = versions.removeIf(candidate -> candidate.version().equals(version));
        if (removed) {
            this.updatedAt = Instant.now();
            if (version.equals(defaultVersion)) {
                // Leaving a default pointing at something uninstalled would make every unpinned node fail with
                // a confusing "no such version" rather than falling back.
                this.defaultVersion = newestUsableVersion().orElse(null);
            }
        }
        return removed;
    }

    /** @return the granted permissions and settings of a version, for the loader */
    public Map<String, Object> settingsOf(String version) {
        return version(version).map(InstalledVersion::settings).orElse(new LinkedHashMap<>());
    }

    @Override
    public String toString() {
        return "InstalledPlugin{" + pluginId + " versions="
                + versions.stream().map(InstalledVersion::version).toList() + "}";
    }
}
