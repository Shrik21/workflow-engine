package com.orchpilot.workflow.node;

import com.orchpilot.workflow.exception.NodeExecutorNotFoundException;
import com.orchpilot.workflow.model.WorkflowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent node registry with a resolver chain.
 *
 * <p>Built-in executors are registered at construction from whatever {@link WorkflowNodeExecutor}
 * beans exist, so adding a built-in node type means adding one bean and nothing else.
 *
 * <p>Resolvers are fetched lazily through an {@link ObjectProvider}. That is not incidental: the
 * plugin resolver depends on the plugin registry, which depends on services that ultimately want to
 * log through the engine, and eager injection here would create a startup cycle.
 */
@Component
public class DefaultWorkflowNodeRegistry implements WorkflowNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowNodeRegistry.class);

    private final ConcurrentHashMap<String, WorkflowNodeExecutor> executors = new ConcurrentHashMap<>();
    private final ObjectProvider<NodeExecutorResolver> resolverProvider;

    public DefaultWorkflowNodeRegistry(List<WorkflowNodeExecutor> builtInExecutors,
                                       ObjectProvider<NodeExecutorResolver> resolverProvider) {
        this.resolverProvider = resolverProvider;
        for (WorkflowNodeExecutor executor : builtInExecutors) {
            register(executor);
        }
        log.info("Node registry initialised with built-in types: {}", executors.keySet());
    }

    @Override
    public Optional<WorkflowNodeExecutor> register(WorkflowNodeExecutor executor) {
        if (executor == null || executor.getNodeType() == null || executor.getNodeType().isBlank()) {
            throw new IllegalArgumentException("A node executor must declare a non-blank node type");
        }
        String nodeType = executor.getNodeType();
        WorkflowNodeExecutor previous = executors.put(nodeType, executor);
        if (previous != null) {
            log.warn("Node type '{}' re-registered: {} replaced {}", nodeType,
                    executor.getClass().getName(), previous.getClass().getName());
        } else {
            log.debug("Registered node type '{}' -> {}", nodeType, executor.getClass().getName());
        }
        return Optional.ofNullable(previous);
    }

    @Override
    public boolean unregister(String nodeType) {
        if (nodeType == null) {
            return false;
        }
        boolean removed = executors.remove(nodeType) != null;
        if (removed) {
            log.info("Unregistered node type '{}'", nodeType);
        }
        return removed;
    }

    @Override
    public Optional<WorkflowNodeExecutor> find(String nodeType) {
        return nodeType == null ? Optional.empty() : Optional.ofNullable(executors.get(nodeType));
    }

    @Override
    public WorkflowNodeExecutor resolve(WorkflowNode node) {
        if (node == null || node.getType() == null || node.getType().isBlank()) {
            throw new NodeExecutorNotFoundException("<missing>", "the node declares no type");
        }
        // A node that names a plugin coordinate always goes through the plugin path, even if some
        // other executor happens to share the type name. Pinning must never be silently ignored.
        if (!node.isPluginNode()) {
            WorkflowNodeExecutor direct = executors.get(node.getType());
            if (direct != null) {
                return direct;
            }
        }
        for (NodeExecutorResolver resolver : orderedResolvers()) {
            Optional<WorkflowNodeExecutor> resolved = resolver.resolve(node);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        WorkflowNodeExecutor direct = executors.get(node.getType());
        if (direct != null) {
            return direct;
        }
        throw new NodeExecutorNotFoundException(node.getType());
    }

    @Override
    public boolean canResolve(WorkflowNode node) {
        try {
            resolve(node);
            return true;
        } catch (NodeExecutorNotFoundException ex) {
            return false;
        }
    }

    @Override
    public Set<String> knownNodeTypes() {
        Set<String> types = new LinkedHashSet<>(executors.keySet());
        for (NodeExecutorResolver resolver : orderedResolvers()) {
            types.addAll(resolver.knownNodeTypes());
        }
        return Set.copyOf(types);
    }

    private List<NodeExecutorResolver> orderedResolvers() {
        List<NodeExecutorResolver> resolvers = new ArrayList<>();
        resolverProvider.forEach(resolvers::add);
        resolvers.sort(AnnotationAwareOrderComparator.INSTANCE);
        return resolvers;
    }
}
