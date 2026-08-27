package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.node.NodeExecutorResolver;
import com.orchpilot.workflow.node.WorkflowNodeExecutor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Teaches the node registry about plugin-contributed node types.
 *
 * <p>Registered after the built-in resolver, so a plugin cannot shadow {@code START} or {@code DECISION} by
 * contributing a node type with the same name.
 *
 * <p>Every plugin node resolves to the same {@link PluginNodeExecutor} instance. The per-plugin state lives
 * on the handle in the registry, not in the executor, which is what makes hot-loading a plugin a registry
 * write rather than a bean-graph change.
 */
@Component
public class PluginNodeExecutorResolver implements NodeExecutorResolver {

    private final PluginNodeExecutor pluginNodeExecutor;
    private final PluginRegistry pluginRegistry;

    public PluginNodeExecutorResolver(PluginNodeExecutor pluginNodeExecutor, PluginRegistry pluginRegistry) {
        this.pluginNodeExecutor = pluginNodeExecutor;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public Optional<WorkflowNodeExecutor> resolve(WorkflowNode node) {
        if (node == null || node.getType() == null) {
            return Optional.empty();
        }
        // Answer for anything that names a plugin, and for any node type a loaded plugin contributes. The
        // executor is returned even when the specific version is missing, so the failure is reported as a
        // clear "plugin not available" result rather than an opaque unknown-node-type error.
        if (node.isPluginNode() || pluginRegistry.findByNodeType(node.getType()).isPresent()) {
            return Optional.of(pluginNodeExecutor);
        }
        return Optional.empty();
    }

    @Override
    public Set<String> knownNodeTypes() {
        Set<String> types = new java.util.LinkedHashSet<>(pluginRegistry.nodeTypes());
        types.add(NodeTypes.PLUGIN);
        return Set.copyOf(types);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
