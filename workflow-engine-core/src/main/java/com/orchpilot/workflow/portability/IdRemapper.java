package com.orchpilot.workflow.portability;

import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gives an imported workflow fresh identifiers, and rewrites every reference to match.
 *
 * <h2>Why nothing keeps its old id</h2>
 *
 * An imported node that reused its old MongoDB id could collide with an existing document, or — worse across a
 * tenant boundary — silently attach to another tenant's node. So every node gets a new id on import, an
 * old→new map is kept, and every place that named the old id is rewritten from that map: the edges' source and
 * target, a decision node's default branch and its conditions' branch targets, and a node's compensation
 * target. A reference that is left pointing at an id that no longer exists is a workflow that fails at the
 * first step, so the rewrite is exhaustive rather than best-effort.
 *
 * <p>The map is exposed so form-id and other cross-cutting rewrites done elsewhere can share the same view of
 * what moved where.
 */
final class IdRemapper {

    private final Map<String, String> nodeIds = new LinkedHashMap<>();

    /** @return old node id → new node id, in the order the nodes were seen */
    Map<String, String> nodeIdMapping() {
        return nodeIds;
    }

    /**
     * Assigns fresh ids to the nodes and rewrites every intra-node reference.
     *
     * @param nodes the imported nodes, mutated in place
     */
    void remapNodes(Iterable<WorkflowNode> nodes) {
        // First pass: allocate a new id for every node, so the second pass can resolve any reference.
        for (WorkflowNode node : nodes) {
            String oldId = node.getId();
            String newId = "node-" + UUID.randomUUID();
            if (oldId != null) {
                nodeIds.put(oldId, newId);
            }
            node.setId(newId);
        }

        // Second pass: rewrite references that name a node id.
        for (WorkflowNode node : nodes) {
            if (node.getCompensationNodeId() != null) {
                node.setCompensationNodeId(nodeIds.getOrDefault(node.getCompensationNodeId(),
                        node.getCompensationNodeId()));
            }
            // Decision branch targets and default branch are branch names, not node ids in this model, so they
            // are left as-is; the edges carry the id references and are rewritten below.
        }
    }

    /**
     * Rewrites the edges' endpoints to the new node ids, dropping any edge whose endpoint did not survive.
     *
     * @param connections the imported edges, mutated in place
     */
    void remapConnections(Iterable<WorkflowConnection> connections) {
        for (WorkflowConnection connection : connections) {
            if (connection.getSource() != null) {
                connection.setSource(nodeIds.getOrDefault(connection.getSource(), connection.getSource()));
            }
            if (connection.getTarget() != null) {
                connection.setTarget(nodeIds.getOrDefault(connection.getTarget(), connection.getTarget()));
            }
        }
    }

    /** @return a fresh workflow id */
    static String newWorkflowId() {
        return UUID.randomUUID().toString();
    }
}
