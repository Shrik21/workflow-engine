package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.sdk.version.SemanticVersion;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares the registry's catalogue against what this engine has installed.
 *
 * <p>The whole point of the marketplace, and the one place the comparison is made. Doing it in the controller, or
 * again in the front end, would produce two answers to "is there an update" that disagree the first time a
 * pre-release is published.
 *
 * <h2>The union, not the intersection</h2>
 *
 * <p>The result covers every plugin either side knows about. A plugin only the registry has is
 * {@link PluginSyncStatus#NOT_INSTALLED}; one only this engine has is
 * {@link PluginSyncStatus#UNKNOWN_TO_REGISTRY}, which is what a locally installed plugin looks like before the first
 * successful sync, and also what a plugin the registry has dropped looks like. Reporting only the catalogue would
 * make an installed plugin vanish from the screen the moment the registry lost it, which is precisely when somebody
 * wants to see it.
 */
@Service
public class PluginStatusService {

    private final PluginCatalogSyncService catalog;
    private final InstalledPluginRepository installed;
    private final PluginCompatibilityService compatibility;

    public PluginStatusService(PluginCatalogSyncService catalog, InstalledPluginRepository installed,
                              PluginCompatibilityService compatibility) {
        this.catalog = catalog;
        this.installed = installed;
        this.compatibility = compatibility;
    }

    /**
     * One row of the marketplace.
     *
     * @param pluginId         stable id
     * @param name             display name, from the catalogue when known and from the installation otherwise
     * @param description      one line
     * @param vendor           publisher
     * @param serverVersion    newest version the registry offers, or null when it offers none
     * @param installedVersion newest usable version here, or null when nothing is installed
     * @param status           the comparison
     * @param compatible       whether this engine can run the registry's newest version
     * @param incompatibility  why not, empty when it can
     * @param installedVersions every version present here, newest first
     * @param availableVersions every version the registry offers, newest first
     * @param nodeTypes        node types the plugin contributes
     * @param deprecatedInstalled whether a version installed here has been deprecated upstream
     */
    public record PluginStatusView(
            String pluginId,
            String name,
            String description,
            String vendor,
            String serverVersion,
            String installedVersion,
            PluginSyncStatus status,
            boolean compatible,
            List<String> incompatibility,
            List<InstalledVersionView> installedVersions,
            List<String> availableVersions,
            List<String> nodeTypes,
            boolean deprecatedInstalled) {

        public PluginStatusView {
            incompatibility = incompatibility == null ? List.of() : List.copyOf(incompatibility);
            installedVersions = installedVersions == null ? List.of() : List.copyOf(installedVersions);
            availableVersions = availableVersions == null ? List.of() : List.copyOf(availableVersions);
            nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
        }
    }

    /**
     * One installed version, as the marketplace shows it.
     *
     * @param version      the version
     * @param state        its local lifecycle state
     * @param isDefault    whether unpinned nodes resolve to it
     * @param installedAt  when
     * @param failure      why it failed, or null
     */
    public record InstalledVersionView(String version, InstallState state, boolean isDefault,
                                       Instant installedAt, String failure) {
    }

    /**
     * The marketplace.
     *
     * @return every plugin either side knows about, most in need of attention first
     */
    public List<PluginStatusView> statuses() {
        Map<String, CatalogRecords.CatalogEntry> available = new LinkedHashMap<>();
        for (CatalogRecords.CatalogEntry entry : catalog.entries()) {
            available.put(entry.pluginId(), entry);
        }
        Map<String, InstalledPlugin> local = new LinkedHashMap<>();
        for (InstalledPlugin plugin : installed.findAllByOrderByPluginIdAsc()) {
            local.put(plugin.getPluginId(), plugin);
        }

        List<String> ids = new ArrayList<>(available.keySet());
        for (String id : local.keySet()) {
            if (!available.containsKey(id)) {
                ids.add(id);
            }
        }

        List<PluginStatusView> views = new ArrayList<>(ids.size());
        for (String id : ids) {
            views.add(view(available.get(id), local.get(id)));
        }

        views.sort(Comparator
                .comparing((PluginStatusView view) -> view.status().ordinal())
                .thenComparing(PluginStatusView::pluginId));
        return views;
    }

    /**
     * @param pluginId the plugin
     * @return its row, or empty when neither side knows it
     */
    public Optional<PluginStatusView> status(String pluginId) {
        CatalogRecords.CatalogEntry entry = catalog.entry(pluginId).orElse(null);
        InstalledPlugin local = installed.findById(pluginId).orElse(null);
        return entry == null && local == null ? Optional.empty() : Optional.of(view(entry, local));
    }

    /**
     * Decides one plugin's status.
     *
     * <p>The order of these tests is the design. Revocation outranks everything because it is the only one that says
     * something already running may be harmful. Incompatibility comes next, because offering an update that cannot
     * load would waste somebody's time. Only then is a version comparison worth making.
     */
    private PluginStatusView view(CatalogRecords.CatalogEntry entry, InstalledPlugin local) {
        String pluginId = entry != null ? entry.pluginId() : local.getPluginId();
        String installedVersion = local == null ? null : local.newestUsableVersion().orElse(null);
        String serverVersion = entry == null ? null : entry.latestVersion();

        PluginCompatibilityService.Compatibility verdict = entry == null
                ? PluginCompatibilityService.Compatibility.ok()
                : compatibility.check(entry);

        List<InstalledVersionView> installedVersions = local == null ? List.of()
                : local.getVersions().stream()
                        .sorted(Comparator.comparing(
                                (InstalledPlugin.InstalledVersion version) ->
                                        SemanticVersion.parse(version.version())).reversed())
                        .map(version -> new InstalledVersionView(version.version(), version.state(),
                                version.version().equals(local.getDefaultVersion()),
                                version.installedAt(), version.failure()))
                        .toList();

        boolean deprecatedInstalled = entry != null && local != null && local.getVersions().stream()
                .anyMatch(version -> entry.version(version.version())
                        .map(CatalogRecords.CatalogVersion::isDeprecated).orElse(false));

        PluginSyncStatus status = statusOf(entry, local, installedVersion, serverVersion, verdict,
                deprecatedInstalled);

        return new PluginStatusView(pluginId,
                entry != null ? entry.name() : local.getName(),
                entry == null ? null : entry.description(),
                entry == null ? null : entry.vendor(),
                serverVersion, installedVersion, status,
                verdict.compatible(), verdict.reasons(), installedVersions,
                entry == null ? List.of()
                        : entry.versions().stream().map(CatalogRecords.CatalogVersion::version).toList(),
                nodeTypesOf(entry, local),
                deprecatedInstalled);
    }

    private PluginSyncStatus statusOf(CatalogRecords.CatalogEntry entry, InstalledPlugin local,
                                      String installedVersion, String serverVersion,
                                      PluginCompatibilityService.Compatibility verdict,
                                      boolean deprecatedInstalled) {
        boolean isInstalled = installedVersion != null;

        if (entry == null) {
            // Installed and absent from the catalogue. Either the registry dropped it or we have never synced.
            return PluginSyncStatus.UNKNOWN_TO_REGISTRY;
        }
        if (entry.isRevoked()) {
            return isInstalled ? PluginSyncStatus.REVOKED : PluginSyncStatus.NOT_INSTALLED;
        }
        if (!isInstalled) {
            // Not installed, so compatibility is what decides whether the Install button is offered at all.
            return verdict.compatible() ? PluginSyncStatus.NOT_INSTALLED : PluginSyncStatus.INCOMPATIBLE;
        }
        if (!verdict.compatible()) {
            return PluginSyncStatus.INCOMPATIBLE;
        }
        if (serverVersion != null && SemanticVersion.isNewer(serverVersion, installedVersion)) {
            return PluginSyncStatus.UPDATE_AVAILABLE;
        }
        if (entry.isDeprecated() || deprecatedInstalled) {
            return PluginSyncStatus.DEPRECATED;
        }
        return PluginSyncStatus.INSTALLED;
    }

    /** Node types from the catalogue when it knows them, from the installation otherwise. */
    private List<String> nodeTypesOf(CatalogRecords.CatalogEntry entry, InstalledPlugin local) {
        if (entry != null && !entry.nodes().isEmpty()) {
            return entry.nodes().stream().map(CatalogRecords.CatalogNode::nodeType).toList();
        }
        if (local == null) {
            return List.of();
        }
        return local.getVersions().stream()
                .flatMap(version -> version.nodeTypes().stream())
                .distinct()
                .toList();
    }

    /**
     * How the catalogue behind these statuses is doing.
     *
     * @param syncedAt   when it was last confirmed current, or null
     * @param stale      whether it is older than the sync interval by a wide margin
     * @param lastError  why the last attempt failed, or null
     * @param configured whether a registry is configured at all
     * @param plugins    how many plugins the catalogue holds
     */
    public record CatalogHealth(Instant syncedAt, boolean stale, String lastError, boolean configured,
                                int plugins) {
    }

    /**
     * @param properties the registry configuration
     * @return the catalogue's health, so a user is told when they are looking at stale data
     */
    public CatalogHealth catalogHealth(PluginServerProperties properties) {
        CatalogCache cached = catalog.cached();
        // Three sync intervals: one missed sync is noise, three in a row is worth saying out loud.
        boolean stale = cached.isStale(properties.getSyncInterval().multipliedBy(3));
        return new CatalogHealth(cached.getSyncedAt(), stale, cached.getLastError(),
                properties.isConfigured(), cached.getEntries().size());
    }
}
