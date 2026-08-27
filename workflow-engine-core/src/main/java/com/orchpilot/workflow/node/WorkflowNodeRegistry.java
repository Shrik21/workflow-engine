package com.orchpilot.workflow.node;

import com.orchpilot.workflow.exception.NodeExecutorNotFoundException;
import com.orchpilot.workflow.model.WorkflowNode;

import java.util.Optional;
import java.util.Set;

/**
 * The engine's node-type lookup table.
 *
 * <p>Registration and lookup are safe under concurrent load: plugins are registered and unregistered
 * while other threads are executing workflows, so this cannot be a plain {@code HashMap} built at
 * startup.
 */
public interface WorkflowNodeRegistry {

    /**
     * Registers or replaces the executor for a node type.
     *
     * @param executor executor to register
     * @return the executor previously registered for the type, or empty
     */
    Optional<WorkflowNodeExecutor> register(WorkflowNodeExecutor executor);

    /**
     * @param nodeType node type to remove
     * @return {@code true} when an executor was removed
     */
    boolean unregister(String nodeType);

    /**
     * @param nodeType node type to look up
     * @return the directly registered executor, or empty; does not consult resolvers
     */
    Optional<WorkflowNodeExecutor> find(String nodeType);

    /**
     * Resolves the executor for a node, consulting registered executors first and then the resolver
     * chain.
     *
     * @param node node to resolve
     * @return an executor, never {@code null}
     * @throws NodeExecutorNotFoundException when nothing can execute the node
     */
    WorkflowNodeExecutor resolve(WorkflowNode node);

    /**
     * @param node node to test
     * @return {@code true} when {@link #resolve(WorkflowNode)} would succeed
     */
    boolean canResolve(WorkflowNode node);

    /**
     * @return every node type this instance can currently execute, built-in and plugin-contributed
     */
    Set<String> knownNodeTypes();
}
