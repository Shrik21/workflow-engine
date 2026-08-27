package com.orchpilot.workflow.node;

import com.orchpilot.workflow.model.WorkflowNode;
import org.springframework.core.Ordered;

import java.util.Optional;
import java.util.Set;

/**
 * A source of node executors, consulted by {@link WorkflowNodeRegistry}.
 *
 * <p>This is the seam that keeps plugin dispatch out of the registry. The registry asks each resolver
 * in order and takes the first answer; built-in types resolve from a static map, plugin types resolve
 * through the plugin registry, and a future out-of-process plugin transport would be a third
 * implementation with no change to the engine.
 */
public interface NodeExecutorResolver extends Ordered {

    /**
     * @param node node to resolve an executor for
     * @return the executor, or empty when this resolver does not handle the node
     */
    Optional<WorkflowNodeExecutor> resolve(WorkflowNode node);

    /**
     * @return node types this resolver can currently handle, for the design-time node catalogue
     */
    Set<String> knownNodeTypes();

    @Override
    default int getOrder() {
        return 0;
    }
}
