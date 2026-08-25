package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginAuditEvent;
import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;
import com.orchpilot.pluginserver.repository.PluginRepository;
import com.orchpilot.pluginserver.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.manifest.PluginManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The plugin head: identity, availability, and which version is the latest.
 *
 * <p>Version-level work lives in {@link PluginVersionService}. The split is along the same line as the documents:
 * this class answers "what is this plugin and is it offered", that one answers "what happened to version 1.2.0".
 */
@Service
public class PluginService {

    private static final Logger log = LoggerFactory.getLogger(PluginService.class);

    /** What a plugin's latest version may be chosen from. */
    private static final EnumSet<PluginStatus> LATEST_CANDIDATES = EnumSet.of(PluginStatus.ACTIVE);

    /** Plugins a workflow service is offered. */
    static final EnumSet<PluginStatus> AVAILABLE = EnumSet.of(PluginStatus.ACTIVE, PluginStatus.DEPRECATED);

    private final PluginRepository plugins;
    private final PluginVersionRepository versions;
    private final PluginAuditService audit;

    public PluginService(PluginRepository plugins, PluginVersionRepository versions,
                        PluginAuditService audit) {
        this.plugins = plugins;
        this.versions = versions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------------- reading

    public Page<Plugin> list(String search, Pageable pageable) {
        if (search == null || search.isBlank()) {
            return plugins.findAll(pageable);
        }
        // Quoted, because the term is interpolated into a regular expression and an unescaped "(" would make
        // the query throw rather than match nothing.
        return plugins.search(Pattern.quote(search.trim()), pageable);
    }

    public Plugin require(String pluginId) {
        return plugins.findById(pluginId)
                .orElseThrow(() -> PluginServerException.pluginNotFound(pluginId));
    }

    public Optional<Plugin> find(String pluginId) {
        return plugins.findById(pluginId);
    }

    /** @return every plugin with something installable in it, for the catalogue */
    public List<Plugin> catalogue() {
        return plugins.findCatalogueEntries(AVAILABLE);
    }

    // --------------------------------------------------------------------- creating

    /**
     * Finds or creates the head for an uploaded version.
     *
     * <p>The head's descriptive fields follow the newest version uploaded, so renaming a plugin is done by
     * publishing a version with the new name rather than by a separate edit. That keeps the registry's copy of
     * "what is this plugin called" traceable to an artefact somebody published.
     *
     * @param manifest the uploaded version's manifest
     * @param actor    who uploaded it
     * @return the head, saved
     */
    Plugin upsertFromManifest(PluginManifest manifest, String actor) {
        Plugin plugin = plugins.findById(manifest.pluginId()).orElseGet(() -> {
            Plugin created = new Plugin();
            created.setPluginId(manifest.pluginId());
            created.setCreatedAt(Instant.now());
            created.setCreatedBy(actor);
            created.setStatus(PluginStatus.ACTIVE);
            log.info("Registering new plugin '{}'", manifest.pluginId());
            return created;
        });

        plugin.setName(manifest.name());
        plugin.setDescription(manifest.description());
        plugin.setVendor(manifest.vendor());
        plugin.setPluginType(manifest.pluginType().name());
        plugin.setUpdatedAt(Instant.now());
        plugin.setUpdatedBy(actor);
        return plugins.save(plugin);
    }

    // -------------------------------------------------------------------- lifecycle

    /**
     * Makes a plugin available again.
     *
     * @param pluginId the plugin
     * @return the plugin
     */
    public Plugin activate(String pluginId) {
        Plugin plugin = require(pluginId);
        return transition(plugin, PluginStatus.ACTIVE, PluginAuditEvent.Action.PUBLISHED);
    }

    /**
     * Withdraws a plugin and everything in it from the catalogue.
     *
     * <p>Versions keep their own states. A workflow service that already installed one keeps working, which is
     * the point: deactivating stops new installs rather than breaking running workflows.
     *
     * @param pluginId the plugin
     * @return the plugin
     */
    public Plugin deactivate(String pluginId) {
        Plugin plugin = require(pluginId);
        return transition(plugin, PluginStatus.INACTIVE, PluginAuditEvent.Action.DEACTIVATED);
    }

    private Plugin transition(Plugin plugin, PluginStatus to, PluginAuditEvent.Action action) {
        if (plugin.getStatus() == to) {
            throw PluginServerException.illegalTransition(plugin.getPluginId(),
                    plugin.getStatus().name(), to.name());
        }
        if (!plugin.getStatus().canTransitionTo(to)) {
            throw PluginServerException.illegalTransition(plugin.getPluginId(),
                    plugin.getStatus().name(), to.name());
        }
        plugin.setStatus(to);
        plugin.setUpdatedAt(Instant.now());
        plugin.setUpdatedBy(PluginAuditService.currentActor());
        Plugin saved = plugins.save(plugin);
        audit.record(plugin.getPluginId(), null, action, "OK", Map.of("status", to.name()));
        log.info("Plugin '{}' is now {}", plugin.getPluginId(), to);
        return saved;
    }

    /**
     * Recomputes the denormalised latest version and version count.
     *
     * <p>Called after anything that changes a version's state. Pre-releases are excluded from being latest, so a
     * release candidate never becomes what an unpinned install resolves to; a plugin whose only versions are
     * pre-releases therefore has no latest version and must be installed by naming one.
     *
     * @param pluginId the plugin to recompute
     */
    void recomputeLatestVersion(String pluginId) {
        Plugin plugin = plugins.findById(pluginId).orElse(null);
        if (plugin == null) {
            return;
        }
        String latest = versions
                .findFirstByPluginIdAndStatusInOrderByOrderMajorDescOrderMinorDescOrderPatchDescOrderReleaseRankDesc(
                        pluginId, LATEST_CANDIDATES)
                .filter(candidate -> candidate.getOrder() != null && !candidate.getOrder().isPreRelease())
                .map(PluginVersion::getVersion)
                .orElse(null);

        long count = versions.countByPluginId(pluginId);
        if (!java.util.Objects.equals(latest, plugin.getLatestVersion())
                || plugin.getVersionCount() != count) {
            plugin.setLatestVersion(latest);
            plugin.setVersionCount((int) count);
            plugin.setUpdatedAt(Instant.now());
            plugins.save(plugin);
            log.info("Plugin '{}' latest version is now {}", pluginId, latest == null ? "none" : latest);
        }
    }

    /**
     * Removes a plugin, its versions and their stored bytes.
     *
     * <p>The one genuinely destructive operation here, and the reason it takes its own permission. Everything
     * else in this registry is a state change, because other services are running these artefacts.
     *
     * @param pluginId the plugin
     * @param deleter  removes each version's bytes and record
     * @return how many versions were removed
     */
    public int delete(String pluginId, java.util.function.Consumer<PluginVersion> deleter) {
        Plugin plugin = require(pluginId);
        List<PluginVersion> all = versions
                .findByPluginIdOrderByOrderMajorDescOrderMinorDescOrderPatchDescOrderReleaseRankDesc(pluginId);
        all.forEach(deleter);
        plugins.delete(plugin);
        audit.record(pluginId, null, PluginAuditEvent.Action.DELETED, "OK",
                Map.of("versionsRemoved", all.size()));
        log.warn("Deleted plugin '{}' and {} version(s)", pluginId, all.size());
        return all.size();
    }
}
