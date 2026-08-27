package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexed, read-only view of a workflow definition's graph.
 *
 * <p>Built once per execution rather than scanning the connection list for every hop, which turns node
 * traversal from O(edges) into O(1) and keeps a thousand-node workflow linear instead of quadratic.
 *
 * <p>Immutable after construction and therefore safe to share.
 */
public final class WorkflowGraph {

    private final Map<String, WorkflowNode> nodesById;
    private final Map<String, List<WorkflowConnection>> outgoing;
    private final Map<String, List<WorkflowConnection>> incoming;
    private final List<WorkflowNode> startNodes;
    private final List<WorkflowNode> endNodes;

    private WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowConnection> connections) {
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        List<WorkflowNode> starts = new ArrayList<>();
        List<WorkflowNode> ends = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (node.getId() == null) {
                continue;
            }
            byId.put(node.getId(), node);
            if (NodeTypes.START.equals(node.getType())) {
                starts.add(node);
            } else if (NodeTypes.END.equals(node.getType())) {
                ends.add(node);
            }
        }
        Map<String, List<WorkflowConnection>> out = new LinkedHashMap<>();
        Map<String, List<WorkflowConnection>> in = new LinkedHashMap<>();
        for (WorkflowConnection connection : connections) {
            if (connection.getSource() != null) {
                out.computeIfAbsent(connection.getSource(), k -> new ArrayList<>()).add(connection);
            }
            if (connection.getTarget() != null) {
                in.computeIfAbsent(connection.getTarget(), k -> new ArrayList<>()).add(connection);
            }
        }
        this.nodesById = Collections.unmodifiableMap(byId);
        this.outgoing = Collections.unmodifiableMap(out);
        this.incoming = Collections.unmodifiableMap(in);
        this.startNodes = List.copyOf(starts);
        this.endNodes = List.copyOf(ends);
    }

    /**
     * @param version published workflow definition
     * @return an indexed graph
     */
    public static WorkflowGraph of(WorkflowVersion version) {
        return new WorkflowGraph(version.getNodes(), version.getConnections());
    }

    /**
     * @param nodes       node list
     * @param connections connection list
     * @return an indexed graph, for validating an unpublished draft
     */
    public static WorkflowGraph of(List<WorkflowNode> nodes, List<WorkflowConnection> connections) {
        return new WorkflowGraph(nodes == null ? List.of() : nodes,
                connections == null ? List.of() : connections);
    }

    /**
     * @param nodeId node id
     * @return the node, or empty
     */
    public Optional<WorkflowNode> node(String nodeId) {
        return nodeId == null ? Optional.empty() : Optional.ofNullable(nodesById.get(nodeId));
    }

    /** @return every node, in definition order */
    public List<WorkflowNode> nodes() {
        return List.copyOf(nodesById.values());
    }

    /** @return every start node; a valid workflow has exactly one */
    public List<WorkflowNode> startNodes() {
        return startNodes;
    }

    /** @return every end node; a valid workflow has at least one */
    public List<WorkflowNode> endNodes() {
        return endNodes;
    }

    /**
     * @param nodeId source node id
     * @return outgoing edges in definition order
     */
    public List<WorkflowConnection> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    /**
     * @param nodeId target node id
     * @return incoming edges in definition order
     */
    public List<WorkflowConnection> incoming(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    /**
     * Chooses the edge to follow after a node.
     *
     * <p>When the node selected a branch, the edge whose {@code sourcePort} matches wins. Otherwise the
     * first edge with no {@code sourcePort} is the default. A named branch with no matching edge falls
     * back to the default edge rather than stalling, because a decision whose "rejected" branch is not
     * wired should still reach the end of the workflow.
     *
     * @param nodeId         node just executed
     * @param selectedBranch branch the node selected, may be {@code null}
     * @return the edge to follow, or empty when the node has no usable outgoing edge
     */
    public Optional<WorkflowConnection> selectEdge(String nodeId, String selectedBranch) {
        List<WorkflowConnection> candidates = outgoing(nodeId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (selectedBranch != null && !selectedBranch.isBlank()) {
            for (WorkflowConnection connection : candidates) {
                if (selectedBranch.equals(connection.getSourcePort())) {
                    return Optional.of(connection);
                }
            }
        }
        for (WorkflowConnection connection : candidates) {
            if (connection.isDefaultEdge()) {
                return Optional.of(connection);
            }
        }
        return Optional.empty();
    }

    /**
     * @param nodeId node id
     * @return {@code true} when nothing points at this node and it is not a start node
     */
    public boolean isOrphan(String nodeId) {
        WorkflowNode node = nodesById.get(nodeId);
        if (node == null) {
            return false;
        }
        return !NodeTypes.START.equals(node.getType()) && incoming(nodeId).isEmpty();
    }
}
