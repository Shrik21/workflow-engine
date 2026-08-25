package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.exception.InvalidWorkflowStateException;
import com.orchpilot.workflow.exception.PluginLoadException;
import com.orchpilot.workflow.exception.PluginNotFoundException;
import com.orchpilot.workflow.exception.PluginValidationException;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.plugin.PluginManager;
import com.orchpilot.workflow.plugin.PluginUploadRequest;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.version.SemanticVersion;
import com.orchpilot.workflow.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Installing, updating and removing plugins that come from the registry.
 *
 * <h2>What this adds, and what it deliberately does not</h2>
 *
 * <p>The engine already knows how to take a plugin archive and make it run: {@link PluginManager} validates it,
 * identifies it by instantiating it in a throwaway class loader, stores it, loads it and registers its node types.
 * The only thing missing for a registry install is where the bytes come from. So this class fetches and proves the
 * bytes, then hands them to that same path rather than opening a second one. A parallel loader would mean two
 * answers to "which versions does this engine have" and two copies of the most security-sensitive code in the
 * platform.
 *
 * <p>What it adds on top is the part the registry makes possible: choosing a version from a catalogue, checking it
 * can run here before downloading it, keeping the old version alive while the new one takes over, and refusing to
 * remove something a published workflow is still running.
 *
 * <h2>Permissions are not carried over</h2>
 *
 * <p>A plugin arrives with no granted hosts and no secret scopes, whatever its manifest requested. The registry
 * records what a plugin asked for; an administrator here decides what it gets. Installing with the requested set
 * would let a plugin author grant themselves access by editing their own manifest, which is the one thing the split
 * between the two services exists to prevent. The response says so, because a plugin that cannot reach anything
 * looks broken until somebody knows why.
 *
 * <h2>One lock per plugin</h2>
 *
 * <p>Two installs of different plugins do not serialise; two operations on the same plugin do. The lock is the same
 * idiom {@link PluginManager} uses, for the same reason: an update that is moving a default version while an
 * uninstall removes it is not a situation worth reasoning about.
 */
@Service
public class PluginInstallationService {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallationService.class);

    private final PluginCatalogSyncService catalog;
    private final InstalledPluginRepository installed;
    private final PluginInstallationRepository history;
    private final PluginVersionRepository versions;
    private final PluginCompatibilityService compatibility;
    private final PluginArchiveDownloader downloader;
    private final PluginUsageService usage;
    private final PluginManager pluginManager;
    private final AuditService audit;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public PluginInstallationService(PluginCatalogSyncService catalog, InstalledPluginRepository installed,
                                     PluginInstallationRepository history, PluginVersionRepository versions,
                                     PluginCompatibilityService compatibility,
                                     PluginArchiveDownloader downloader, PluginUsageService usage,
                                     PluginManager pluginManager, AuditService audit) {
        this.catalog = catalog;
        this.installed = installed;
        this.history = history;
        this.versions = versions;
        this.compatibility = compatibility;
        this.downloader = downloader;
        this.usage = usage;
        this.pluginManager = pluginManager;
        this.audit = audit;
    }

    /**
     * What an installation operation did.
     *
     * @param pluginId                the plugin
     * @param version                 the version the operation ended on
     * @param outcome                 what happened
     * @param state                   the version's local state afterwards, null after an uninstall
     * @param nodeTypes               node types now available from it
     * @param previousVersion         for an update, what was in use before
     * @param previousVersionRetained whether the previous version was left loaded because something still needs it
     * @param warnings                things a user should know, such as a deprecated version or absent permissions
     * @param message                 one sentence describing the result
     */
    public record InstallationResult(String pluginId, String version, Outcome outcome, InstallState state,
                                     List<String> nodeTypes, String previousVersion,
                                     boolean previousVersionRetained, List<String> warnings,
                                     String message) {

        public InstallationResult {
            nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** The kinds of thing that can happen. */
        public enum Outcome {

            /** A version this engine did not have is now installed and loaded. */
            INSTALLED,

            /** A newer version was installed and is now the default. */
            UPDATED,

            /** Nothing was done because the version was already here. */
            ALREADY_INSTALLED,

            /** Nothing was done because no newer version exists. */
            ALREADY_CURRENT,

            /** The version was unloaded and removed. */
            UNINSTALLED,

            /** An installed version was loaded. */
            ACTIVATED,

            /** An installed version was unloaded. */
            DEACTIVATED
        }
    }

    // ---------------------------------------------------------------------------------------------- install

    /**
     * Installs one version of a plugin from the registry.
     *
     * @param pluginId  the plugin
     * @param requested the version, or null for the registry's latest release
     * @return what happened
     */
    public InstallationResult install(String pluginId, String requested) {
        String actor = CurrentUser.actorOrSystem();
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            CatalogRecords.CatalogEntry entry = requireEntry(pluginId);
            CatalogRecords.CatalogVersion row = requireInstallableVersion(entry, requested);
            return doInstall(entry, row, actor, false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Installs the registry's latest release alongside the running one, moves the default to it, and retires the
     * old version.
     *
     * <p>The order matters. The new version is installed and loaded before anything about the old one changes, so a
     * failed download leaves the engine exactly as it was. Only once the new version is running does the default
     * move, and only then is the old one drained. An update that cannot complete degrades to "both versions
     * installed, new one default", which is a supported state rather than a broken one.
     *
     * @param pluginId the plugin
     * @return what happened
     */
    public InstallationResult update(String pluginId) {
        String actor = CurrentUser.actorOrSystem();
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            CatalogRecords.CatalogEntry entry = requireEntry(pluginId);
            InstalledPlugin local = installed.findById(pluginId)
                    .orElseThrow(() -> new PluginNotFoundException(pluginId));
            String current = local.newestUsableVersion()
                    .orElseThrow(() -> new PluginValidationException("No usable version of '" + pluginId
                            + "' is installed, so there is nothing to update. Install it first."));

            CatalogRecords.CatalogVersion row = requireInstallableVersion(entry, null);
            if (!SemanticVersion.isNewer(row.version(), current)) {
                return new InstallationResult(pluginId, current, InstallationResult.Outcome.ALREADY_CURRENT,
                        local.version(current).map(InstalledPlugin.InstalledVersion::state).orElse(null),
                        List.of(), current, false, List.of(),
                        "The registry offers nothing newer than the installed " + current + ".");
            }

            InstallationResult installedResult = doInstall(entry, row, actor, true);
            Retirement retirement = retire(pluginId, current, actor);

            record(pluginId, row.version(), PluginInstallation.Action.UPDATE,
                    PluginInstallation.Outcome.OK, actor, current, row.checksum(), retirement.detail(), 0);

            List<String> warnings = new ArrayList<>(installedResult.warnings());
            if (retirement.retained()) {
                warnings.add(retirement.detail());
            }
            return new InstallationResult(pluginId, row.version(), InstallationResult.Outcome.UPDATED,
                    InstallState.ACTIVE, installedResult.nodeTypes(), current, retirement.retained(),
                    warnings, "Updated " + pluginId + " from " + current + " to " + row.version() + ".");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Downloads, verifies and installs, recording every step on the installation record.
     *
     * <p>The record is written before the download starts, so an install that dies half way leaves a
     * {@code DOWNLOADING} row somebody can see rather than no trace at all.
     */
    private InstallationResult doInstall(CatalogRecords.CatalogEntry entry,
                                         CatalogRecords.CatalogVersion row, String actor,
                                         boolean makeDefault) {
        String pluginId = entry.pluginId();
        String version = row.version();
        long startedAt = System.currentTimeMillis();

        InstalledPlugin local = installed.findById(pluginId).orElseGet(() -> fresh(entry, actor));
        local.setName(entry.name());

        Optional<InstalledPlugin.InstalledVersion> existing = local.version(version);
        if (existing.map(candidate -> candidate.state().isTransient()).orElse(false)) {
            throw new InvalidWorkflowStateException("An install of " + pluginId + ":" + version
                    + " is already in progress.");
        }
        if (existing.map(InstalledPlugin.InstalledVersion::isUsable).orElse(false)
                || versions.existsByPluginIdAndVersion(pluginId, version)) {
            // Already here, possibly from a manual upload before this engine knew the registry. Adopting it is
            // more useful than refusing: the marketplace should stop offering an install for something present.
            adopt(local, pluginId, version, row, actor);
            return new InstallationResult(pluginId, version,
                    InstallationResult.Outcome.ALREADY_INSTALLED,
                    local.version(version).map(InstalledPlugin.InstalledVersion::state).orElse(null),
                    nodeTypesOf(pluginId, version), null, false, List.of(),
                    pluginId + ":" + version + " is already installed on this engine.");
        }

        local.put(new InstalledPlugin.InstalledVersion(version, InstallState.DOWNLOADING, row.checksum(),
                null, row.fileSize(), row.sdkVersion(), row.nodeTypes(), Map.of(), Map.of(), Instant.now(),
                actor, null, null));
        local = save(local);

        try {
            PluginArchiveDownloader.VerifiedArchive archive = downloader.fetch(pluginId, version,
                    row.checksum());

            local = transition(local, version, InstallState.VALIDATING);

            PluginVersion document = pluginManager.install(archive.fileName(), archive.content(),
                    installRequest(pluginId, version, archive.sha256(), actor));

            local.put(new InstalledPlugin.InstalledVersion(version, InstallState.ACTIVE, archive.sha256(),
                    archive.cachePath(), archive.size(), row.sdkVersion(), document.getNodeTypes(),
                    Map.of(), new LinkedHashMap<>(), Instant.now(), actor, Instant.now(), null));
            if (makeDefault || local.getDefaultVersion() == null) {
                pluginManager.setDefaultVersion(pluginId, version, actor);
                local.setDefaultVersion(version);
            }
            local = save(local);

            long elapsed = System.currentTimeMillis() - startedAt;
            record(pluginId, version, PluginInstallation.Action.INSTALL, PluginInstallation.Outcome.OK,
                    actor, null, archive.sha256(), "Installed from the registry", elapsed);
            audit.record(actor, "PLUGIN_INSTALLED_FROM_REGISTRY", "PLUGIN", pluginId + ":" + version, "OK",
                    Map.of("sha256", archive.sha256(), "nodeTypes", document.getNodeTypes(),
                            "sizeBytes", archive.size()));
            log.info("Installed {}:{} from the registry in {} ms, contributing {}", pluginId, version,
                    elapsed, document.getNodeTypes());

            return new InstallationResult(pluginId, version, InstallationResult.Outcome.INSTALLED,
                    InstallState.ACTIVE, document.getNodeTypes(), null, false,
                    warningsFor(entry, row), "Installed " + pluginId + ":" + version + ".");
        } catch (RuntimeException ex) {
            failed(local, version, ex.getMessage());
            downloader.release(pluginId, version);
            record(pluginId, version, PluginInstallation.Action.INSTALL, PluginInstallation.Outcome.FAILED,
                    actor, null, row.checksum(), ex.getMessage(),
                    System.currentTimeMillis() - startedAt);
            audit.record(actor, "PLUGIN_INSTALLED_FROM_REGISTRY", "PLUGIN", pluginId + ":" + version,
                    "FAILED", Map.of("reason", String.valueOf(ex.getMessage())));
            log.error("Could not install {}:{} from the registry: {}", pluginId, version, ex.getMessage());
            throw ex;
        }
    }

    // -------------------------------------------------------------------------------------------- uninstall

    /**
     * Removes one installed version.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    public InstallationResult uninstall(String pluginId, String version) {
        String actor = CurrentUser.actorOrSystem();
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            InstalledPlugin local = installed.findById(pluginId)
                    .orElseThrow(() -> new PluginNotFoundException(pluginId));
            InstalledPlugin.InstalledVersion target = local.version(version)
                    .orElseThrow(() -> new PluginNotFoundException(pluginId, version));

            boolean resolvedByDefault = version.equals(local.getDefaultVersion())
                    || local.getVersions().stream().filter(InstalledPlugin.InstalledVersion::isUsable)
                            .count() <= 1;
            List<PluginUsageService.Usage> dependents = usage.dependents(pluginId, version,
                    target.nodeTypes(), resolvedByDefault);

            if (!dependents.isEmpty()) {
                String workflows = dependents.stream().map(PluginUsageService.Usage::describe).distinct()
                        .limit(10).reduce((left, right) -> left + ", " + right).orElse("");
                String reason = "Published workflows still use " + pluginId + ":" + version + ": " + workflows
                        + ". Repoint or unpublish them first.";
                record(pluginId, version, PluginInstallation.Action.UNINSTALL,
                        PluginInstallation.Outcome.REFUSED, actor, null, target.checksum(), reason, 0);
                throw new InvalidWorkflowStateException(reason);
            }

            long startedAt = System.currentTimeMillis();
            // force=false: an execution inside the plugin right now is a reason to refuse, not to break it.
            pluginManager.unload(pluginId, version, false);
            pluginManager.delete(pluginId, version, actor);

            local.remove(version);
            if (local.getVersions().isEmpty()) {
                installed.delete(local);
            } else {
                save(local);
            }
            downloader.release(pluginId, version);

            long elapsed = System.currentTimeMillis() - startedAt;
            record(pluginId, version, PluginInstallation.Action.UNINSTALL, PluginInstallation.Outcome.OK,
                    actor, null, target.checksum(), "Removed", elapsed);
            audit.record(actor, "PLUGIN_UNINSTALLED", "PLUGIN", pluginId + ":" + version, "OK", null);
            log.info("Uninstalled {}:{}", pluginId, version);

            return new InstallationResult(pluginId, version, InstallationResult.Outcome.UNINSTALLED, null,
                    List.of(), null, false, List.of(), "Removed " + pluginId + ":" + version + ".");
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    /**
     * Loads an installed version that is not currently loaded.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    public InstallationResult activate(String pluginId, String version) {
        String actor = CurrentUser.actorOrSystem();
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            requireInstalled(pluginId, version);
            List<String> nodeTypes = pluginManager.activate(pluginId, version, actor).nodeTypes();
            InstalledPlugin local = markState(pluginId, version, InstallState.ACTIVE);
            if (local.getDefaultVersion() == null) {
                pluginManager.setDefaultVersion(pluginId, version, actor);
                local.setDefaultVersion(version);
                save(local);
            }
            record(pluginId, version, PluginInstallation.Action.ACTIVATE, PluginInstallation.Outcome.OK,
                    actor, null, null, "Loaded", 0);
            return new InstallationResult(pluginId, version, InstallationResult.Outcome.ACTIVATED,
                    InstallState.ACTIVE, nodeTypes, null, false, List.of(),
                    "Activated " + pluginId + ":" + version + ".");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Unloads an installed version without removing it.
     *
     * <p>The kill switch. It refuses while a published workflow pins the version, for the same reason an uninstall
     * does: the effect on those workflows is identical.
     *
     * @param pluginId the plugin
     * @param version  the version
     * @return what happened
     */
    public InstallationResult deactivate(String pluginId, String version) {
        String actor = CurrentUser.actorOrSystem();
        ReentrantLock lock = lockFor(pluginId);
        lock.lock();
        try {
            InstalledPlugin.InstalledVersion target = requireInstalled(pluginId, version);
            boolean resolvedByDefault = version.equals(
                    installed.findById(pluginId).map(InstalledPlugin::getDefaultVersion).orElse(null));
            List<PluginUsageService.Usage> dependents = usage.dependents(pluginId, version,
                    target.nodeTypes(), resolvedByDefault);
            if (!dependents.isEmpty()) {
                String reason = "Published workflows still use " + pluginId + ":" + version
                        + ". Deactivating it would fail their executions.";
                record(pluginId, version, PluginInstallation.Action.DEACTIVATE,
                        PluginInstallation.Outcome.REFUSED, actor, null, target.checksum(), reason, 0);
                throw new InvalidWorkflowStateException(reason);
            }

            pluginManager.deactivate(pluginId, version, actor);
            markState(pluginId, version, InstallState.DISABLED);
            record(pluginId, version, PluginInstallation.Action.DEACTIVATE, PluginInstallation.Outcome.OK,
                    actor, null, target.checksum(), "Unloaded", 0);
            return new InstallationResult(pluginId, version, InstallationResult.Outcome.DEACTIVATED,
                    InstallState.DISABLED, List.of(), null, false, List.of(),
                    "Deactivated " + pluginId + ":" + version + ".");
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param pluginId the plugin, or null for every plugin
     * @return the installation history, newest first
     */
    public List<PluginInstallation> history(String pluginId) {
        return pluginId == null || pluginId.isBlank()
                ? history.findTop100ByOrderByAtDesc()
                : history.findByPluginIdOrderByAtDesc(pluginId);
    }

    // --------------------------------------------------------------------------------------------- helpers

    /**
     * Winds down the version an update has just superseded.
     *
     * <p>Retention is the safe answer and is not a failure. A version a published workflow pins, or one with an
     * execution still inside it, stays loaded; the engine is designed to run several versions at once precisely so
     * this case does not have to be resolved immediately.
     */
    private Retirement retire(String pluginId, String version, String actor) {
        InstalledPlugin.InstalledVersion previous = installed.findById(pluginId)
                .flatMap(local -> local.version(version)).orElse(null);
        List<String> nodeTypes = previous == null ? List.of() : previous.nodeTypes();

        if (usage.isPinnedByPublishedWorkflow(pluginId, version, nodeTypes)) {
            return new Retirement(true, "Version " + version + " is still loaded because published workflows "
                    + "pin it.");
        }
        try {
            pluginManager.unload(pluginId, version, false);
        } catch (PluginLoadException ex) {
            return new Retirement(true, "Version " + version + " is still loaded because executions are "
                    + "still running inside it.");
        }
        // Persist the status too, so a restart does not load the retired version again.
        pluginManager.deactivate(pluginId, version, actor);
        markState(pluginId, version, InstallState.INSTALLED);
        return new Retirement(false, "Version " + version + " was drained and unloaded.");
    }

    private record Retirement(boolean retained, String detail) {
    }

    private CatalogRecords.CatalogEntry requireEntry(String pluginId) {
        return catalog.entry(pluginId).orElseThrow(() -> new PluginNotFoundException(pluginId));
    }

    /**
     * Picks the version to install and refuses the ones that must not be.
     *
     * <p>Checked here rather than trusting the marketplace's status: a catalogue row can be minutes old, and the
     * decision to load third-party code into this JVM should be made against the freshest thing available at the
     * moment somebody asks for it.
     */
    private CatalogRecords.CatalogVersion requireInstallableVersion(CatalogRecords.CatalogEntry entry,
                                                                   String requested) {
        if (entry.isRevoked()) {
            throw new PluginValidationException("The registry has revoked '" + entry.pluginId()
                    + "'. It cannot be installed.");
        }
        String wanted = requested == null || requested.isBlank() ? entry.latestVersion() : requested;
        if (wanted == null) {
            throw new PluginValidationException("The registry publishes no installable release of '"
                    + entry.pluginId() + "'. It may have only pre-releases.");
        }
        CatalogRecords.CatalogVersion row = entry.version(wanted)
                .orElseThrow(() -> new PluginNotFoundException(entry.pluginId(), wanted));

        PluginCompatibilityService.Compatibility verdict = compatibility.check(
                row.sdkVersion() == null ? entry.sdkVersion() : row.sdkVersion(),
                entry.javaVersion(), entry.engineCompatibility());
        if (!verdict.compatible()) {
            throw new PluginValidationException("This engine cannot run " + entry.pluginId() + ":" + wanted
                    + ". " + verdict.summary());
        }
        return row;
    }

    private InstalledPlugin.InstalledVersion requireInstalled(String pluginId, String version) {
        return installed.findById(pluginId)
                .flatMap(local -> local.version(version))
                .orElseThrow(() -> new PluginNotFoundException(pluginId, version));
    }

    /**
     * Requests an install with no permissions.
     *
     * <p>The expected id, version and checksum are all passed: {@link PluginManager} re-checks them against what the
     * archive says about itself, which is what catches an archive whose contents do not match the catalogue row that
     * described it.
     */
    private PluginUploadRequest installRequest(String pluginId, String version, String checksum,
                                               String actor) {
        return new PluginUploadRequest(pluginId, version, null, null, List.of(), List.of(), Map.of(),
                List.of(), checksum, true, true, actor);
    }

    private List<String> warningsFor(CatalogRecords.CatalogEntry entry, CatalogRecords.CatalogVersion row) {
        List<String> warnings = new ArrayList<>();
        warnings.add("Installed with no allowed hosts and no secret scopes. Grant what it needs in the "
                + "plugin's settings before using it.");
        if (row.isDeprecated() || entry.isDeprecated()) {
            warnings.add("The registry marks this version as deprecated. It still runs, but a newer one is "
                    + "expected to replace it.");
        }
        return warnings;
    }

    private InstalledPlugin fresh(CatalogRecords.CatalogEntry entry, String actor) {
        InstalledPlugin plugin = new InstalledPlugin();
        plugin.setPluginId(entry.pluginId());
        plugin.setName(entry.name());
        plugin.setCreatedAt(Instant.now());
        plugin.setUpdatedAt(Instant.now());
        log.debug("First installation of plugin {} on this engine, requested by {}", entry.pluginId(), actor);
        return plugin;
    }

    /**
     * Records a version this engine already had as installed, so the marketplace stops offering to install it.
     */
    private void adopt(InstalledPlugin local, String pluginId, String version,
                       CatalogRecords.CatalogVersion row, String actor) {
        if (local.version(version).map(InstalledPlugin.InstalledVersion::isUsable).orElse(false)) {
            return;
        }
        PluginVersion document = versions.findByPluginIdAndVersion(pluginId, version).orElse(null);
        InstallState state = document != null
                && document.getStatus() == com.orchpilot.workflow.model.PluginStatus.ACTIVE
                ? InstallState.ACTIVE : InstallState.INSTALLED;
        local.put(new InstalledPlugin.InstalledVersion(version, state,
                document == null ? row.checksum() : document.getSha256(), null,
                document == null ? row.fileSize() : document.getJarSizeBytes(), row.sdkVersion(),
                document == null ? row.nodeTypes() : document.getNodeTypes(), Map.of(), Map.of(),
                Instant.now(), actor, null, null));
        if (local.getDefaultVersion() == null) {
            local.setDefaultVersion(version);
        }
        save(local);
        log.info("Adopted the already-present {}:{} into the installation record", pluginId, version);
    }

    private List<String> nodeTypesOf(String pluginId, String version) {
        return versions.findByPluginIdAndVersion(pluginId, version)
                .map(PluginVersion::getNodeTypes)
                .orElse(List.of());
    }

    /**
     * Moves one version to a new state on a document already in hand.
     *
     * <p>Takes the document rather than re-reading it. An install performs several transitions in a row, and
     * re-reading between them would both cost round trips and make each step depend on the previous write having
     * become visible.
     */
    private InstalledPlugin transition(InstalledPlugin local, String version, InstallState state) {
        local.version(version).ifPresent(current -> local.put(current.withState(state)));
        return save(local);
    }

    /** As {@link #transition}, for callers that have only the coordinate. */
    private InstalledPlugin markState(String pluginId, String version, InstallState state) {
        InstalledPlugin local = installed.findById(pluginId)
                .orElseThrow(() -> new PluginNotFoundException(pluginId));
        return transition(local, version, state);
    }

    /**
     * Records a failure on the version, without letting the bookkeeping mask the original error.
     */
    private void failed(InstalledPlugin local, String version, String reason) {
        try {
            if (local == null) {
                return;
            }
            local.version(version).ifPresent(current -> local.put(current.withFailure(
                    reason == null ? "unknown error" : reason)));
            save(local);
        } catch (RuntimeException ex) {
            log.warn("Could not record a failed install of version {}: {}", version, ex.getMessage());
        }
    }

    private InstalledPlugin save(InstalledPlugin local) {
        local.setUpdatedAt(Instant.now());
        if (local.getCreatedAt() == null) {
            local.setCreatedAt(Instant.now());
        }
        return installed.save(local);
    }

    /** Writes one history row. Must never be the reason an operation fails. */
    private void record(String pluginId, String version, PluginInstallation.Action action,
                        PluginInstallation.Outcome outcome, String actor, String fromVersion,
                        String checksum, String detail, long durationMillis) {
        try {
            PluginInstallation entry = PluginInstallation.of(pluginId, version, action, outcome, actor);
            entry.setFromVersion(fromVersion);
            entry.setChecksum(checksum);
            entry.setDetail(detail);
            entry.setDurationMillis(durationMillis);
            history.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Could not write the installation history row for {}:{}: {}", pluginId, version,
                    ex.getMessage());
        }
    }

    private ReentrantLock lockFor(String pluginId) {
        return locks.computeIfAbsent(pluginId == null ? "" : pluginId, key -> new ReentrantLock());
    }
}
