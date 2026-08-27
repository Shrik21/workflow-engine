package com.orchpilot.workflow.pluginserver;

import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.repository.WorkflowRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Which published workflows depend on a plugin version.
 *
 * <h2>Why uninstalling asks first</h2>
 *
 * <p>Removing a plugin version that a published workflow runs does not fail at removal time. It fails later, in
 * production, when an execution reaches the node and the engine reports an unavailable plugin for a workflow nobody
 * has edited in months. Refusing the uninstall and naming the workflows turns that into a decision somebody makes
 * with the facts in front of them.
 *
 * <h2>Pinned and unpinned uses are different questions</h2>
 *
 * <p>A node that pins {@code sendgrid:1.0.0} breaks if and only if that exact version goes. A node that names the
 * plugin without a version, or names one of its node types directly, resolves through the default version, so it
 * breaks when the version being removed is the one it would have resolved to. Conflating the two either blocks a
 * removal that is safe or permits one that is not.
 */
@Service
public class PluginUsageService {

    private final WorkflowRepository workflows;

    public PluginUsageService(WorkflowRepository workflows) {
        this.workflows = workflows;
    }

    /**
     * One node in one workflow that depends on a plugin.
     *
     * @param workflowId   the workflow
     * @param workflowName its name, so a refusal can be read without a second lookup
     * @param nodeId       the node
     * @param nodeName     the node's label
     * @param pinned       whether the node names an exact version, rather than resolving through the default
     */
    public record Usage(String workflowId, String workflowName, String nodeId, String nodeName,
                        boolean pinned) {

        /** @return a short human-readable reference, for a message a user reads */
        public String describe() {
            return workflowName + " (node " + (nodeName == null || nodeName.isBlank() ? nodeId : nodeName)
                    + ")";
        }
    }

    /**
     * Published workflows that would break if this exact version were removed.
     *
     * @param pluginId    the plugin
     * @param version     the version about to be removed
     * @param nodeTypes   node types that version contributes
     * @param isResolvedByDefault whether an unpinned node would currently resolve to this version, which is true
     *                    when it is the plugin's default version or the only usable one left
     * @return every dependent node, empty when the version can be removed safely
     */
    public List<Usage> dependents(String pluginId, String version, Collection<String> nodeTypes,
                                  boolean isResolvedByDefault) {
        Set<String> types = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
        List<Usage> dependents = new ArrayList<>();

        for (Workflow workflow : workflows.findUsingPlugin(WorkflowStatus.PUBLISHED, pluginId, types)) {
            for (WorkflowNode node : workflow.getNodes()) {
                if (!mentions(node, pluginId, types)) {
                    continue;
                }
                String pinnedVersion = node.getPluginVersion();
                boolean pinned = pinnedVersion != null && !pinnedVersion.isBlank();

                if (pinned && pinnedVersion.equals(version)) {
                    dependents.add(usage(workflow, node, true));
                } else if (!pinned && isResolvedByDefault) {
                    dependents.add(usage(workflow, node, false));
                }
            }
        }
        return dependents;
    }

    /**
     * @param pluginId  the plugin
     * @param version   the version
     * @param nodeTypes node types it contributes
     * @return whether any published workflow pins this exact version, which is what makes it unsafe to unload
     *         during an update even though a newer version is available
     */
    public boolean isPinnedByPublishedWorkflow(String pluginId, String version,
                                               Collection<String> nodeTypes) {
        return dependents(pluginId, version, nodeTypes, false).stream().anyMatch(Usage::pinned);
    }

    private static boolean mentions(WorkflowNode node, String pluginId, Set<String> nodeTypes) {
        if (node == null) {
            return false;
        }
        if (pluginId.equals(node.getPluginId())) {
            return true;
        }
        // A node that names a plugin's node type directly is as dependent on it as one naming the plugin, and
        // is the usual shape produced by the designer's palette.
        return node.getPluginId() == null && node.getType() != null && nodeTypes.contains(node.getType());
    }

    private static Usage usage(Workflow workflow, WorkflowNode node, boolean pinned) {
        return new Usage(workflow.getId(), workflow.getName(), node.getId(), node.getName(), pinned);
    }
}
