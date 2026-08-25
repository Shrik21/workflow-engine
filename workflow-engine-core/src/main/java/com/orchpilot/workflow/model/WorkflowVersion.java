package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a workflow definition taken at publish time.
 *
 * <p>This is what the engine executes. Nothing mutates it, which is what makes long-running and
 * resumed executions deterministic: a form parked for three days resumes against the definition it
 * started on, even if the workflow has been edited and republished twice since.
 */
@Document(collection = "workflow_versions")
@CompoundIndex(name = "workflow_version_unique", def = "{'workflowId': 1, 'version': -1}", unique = true)
public class WorkflowVersion {

    /** {@code workflowId:version}. */
    @Id
    private String id;

    @Indexed
    private String workflowId;

    private int version;
    private String name;
    private String description;

    private List<WorkflowNode> nodes = new ArrayList<>();
    private List<WorkflowConnection> connections = new ArrayList<>();
    private Map<String, Object> variables = new LinkedHashMap<>();
    private List<WorkflowTrigger> triggers = new ArrayList<>();

    /** Fingerprint of the definition, used to detect a republish of identical content. */
    private String definitionHash;

    private Instant publishedAt;
    private String publishedBy;

    public WorkflowVersion() {
    }

    /**
     * @param workflowId workflow id
     * @param version    version number
     * @return the deterministic document id for this coordinate
     */
    public static String idFor(String workflowId, int version) {
        return workflowId + ":" + version;
    }

    /**
     * @param nodeId node id to find
     * @return the node, or {@code null} when absent
     */
    public WorkflowNode findNode(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        for (WorkflowNode node : nodes) {
            if (nodeId.equals(node.getId())) {
                return node;
            }
        }
        return null;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<WorkflowNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    public List<WorkflowConnection> getConnections() {
        return connections;
    }

    public void setConnections(List<WorkflowConnection> connections) {
        this.connections = connections == null ? new ArrayList<>() : connections;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new LinkedHashMap<>() : variables;
    }

    public List<WorkflowTrigger> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<WorkflowTrigger> triggers) {
        this.triggers = triggers == null ? new ArrayList<>() : triggers;
    }

    public String getDefinitionHash() {
        return definitionHash;
    }

    public void setDefinitionHash(String definitionHash) {
        this.definitionHash = definitionHash;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }
}
