package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.version.SemanticVersion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the registry knows that should stop, or qualify, a publish.
 *
 * <h2>The gap this fills</h2>
 *
 * <p>The workflow validator already checks a plugin node against what this engine has: installed, ACTIVE, loaded,
 * and configured against the schema the plugin published. All of that is local knowledge, and it stays correct
 * whether or not a registry exists.
 *
 * <p>None of it can answer the question the registry alone can: has this version been <em>withdrawn</em>? A revoked
 * plugin is still installed, still ACTIVE and still loaded, so every existing check passes while the publisher of
 * that plugin is telling every engine to stop using it. Publishing a new workflow against it is the one moment
 * where that fact is cheap to act on.
 *
 * <h2>Only revocation blocks</h2>
 *
 * <p>Revocation is the single upstream state that says something already running may be harmful, so it is the only
 * one that refuses a publish. Deprecation and a newer available version are reported as warnings: both describe a
 * plugin that works, and refusing to publish a workflow because a newer release exists would make every publish
 * hostage to somebody else's release schedule.
 *
 * <h2>Silence when there is nothing to say</h2>
 *
 * <p>Every check here degrades to saying nothing. An engine with no registry configured, one whose catalogue has
 * never synced, or one whose registry has been unreachable for a week, publishes exactly as it did before this
 * existed. That is deliberate: the catalogue is a cache of another service's opinion, and a workflow must not
 * become unpublishable because that service is down.
 */
@Service
public class PluginPublishPolicy {

    private final PluginCatalogSyncService catalog;
    private final InstalledPluginRepository installed;

    public PluginPublishPolicy(PluginCatalogSyncService catalog, InstalledPluginRepository installed) {
        this.catalog = catalog;
        this.installed = installed;
    }

    /**
     * Reasons this node must not be published.
     *
     * @param node the node to check; non-plugin nodes yield nothing
     * @return the blocking problems, empty when there are none
     */
    public List<String> errors(WorkflowNode node) {
        List<String> errors = new ArrayList<>();
        if (!isPluginNode(node)) {
            return errors;
        }
        String pluginId = node.getPluginId();
        CatalogRecords.CatalogEntry entry = catalog.entry(pluginId).orElse(null);
        if (entry == null) {
            // Not in the catalogue: either no registry, never synced, or the registry dropped it. None of those
            // is a reason to refuse a workflow whose plugin is installed and loaded here.
            return errors;
        }

        if (entry.isRevoked()) {
            errors.add("Node '" + node.getId() + "' uses plugin '" + pluginId
                    + "', which the registry has revoked. It must not be used in a new publish. Replace the "
                    + "node, or have the revocation lifted upstream.");
            return errors;
        }

        String pinned = pinnedVersion(node);
        if (pinned != null) {
            Optional<CatalogRecords.CatalogVersion> row = entry.version(pinned);
            if (row.isPresent() && "REVOKED".equals(row.get().status())) {
                errors.add("Node '" + node.getId() + "' pins plugin '" + pluginId + ":" + pinned
                        + "', which the registry has revoked. Repoint it at a version that has not been "
                        + "withdrawn.");
            }
        }
        return errors;
    }

    /**
     * Things worth saying about this node without blocking the publish.
     *
     * @param node the node to check; non-plugin nodes yield nothing
     * @return the warnings, empty when there are none
     */
    public List<String> warnings(WorkflowNode node) {
        List<String> warnings = new ArrayList<>();
        if (!isPluginNode(node)) {
            return warnings;
        }
        String pluginId = node.getPluginId();
        CatalogRecords.CatalogEntry entry = catalog.entry(pluginId).orElse(null);
        if (entry == null || entry.isRevoked()) {
            // Revocation is already an error; repeating it as a warning would say the same thing twice.
            return warnings;
        }

        String pinned = pinnedVersion(node);
        if (pinned == null) {
            return warnings;
        }

        entry.version(pinned)
                .filter(CatalogRecords.CatalogVersion::isDeprecated)
                .ifPresent(row -> warnings.add("Node '" + node.getId() + "' pins plugin '" + pluginId + ":"
                        + pinned + "', which the registry has deprecated. It still runs, and a newer release "
                        + "is expected to replace it."));

        newerInstalled(pluginId, pinned).ifPresent(newer -> warnings.add("Node '" + node.getId()
                + "' pins plugin '" + pluginId + ":" + pinned + "' while " + newer
                + " is installed on this engine. The pin is honoured exactly, so this node keeps running "
                + pinned + " until it is repointed."));

        return warnings;
    }

    /**
     * The newest usable version installed here, when it is newer than the pinned one.
     *
     * <p>Compared by semantic precedence rather than by string, so 1.10.0 counts as newer than 1.9.0. An
     * unparseable version on either side yields nothing: a version this engine cannot order is not one it should
     * be advising anybody about.
     */
    private Optional<String> newerInstalled(String pluginId, String pinned) {
        return installed.findById(pluginId)
                .flatMap(InstalledPlugin::newestUsableVersion)
                .filter(newest -> SemanticVersion.tryParse(newest).isPresent()
                        && SemanticVersion.tryParse(pinned).isPresent()
                        && SemanticVersion.isNewer(newest, pinned));
    }

    /** A node backed by a plugin that names which plugin it is. A bare node type carries no coordinate. */
    private boolean isPluginNode(WorkflowNode node) {
        return node != null && node.getPluginId() != null && !node.getPluginId().isBlank();
    }

    private String pinnedVersion(WorkflowNode node) {
        String version = node.getPluginVersion();
        return version == null || version.isBlank() ? null : version;
    }
}
