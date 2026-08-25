package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable head of a workflow definition.
 *
 * <p>Executions never read this document. Publishing snapshots it into an immutable
 * {@link WorkflowVersion} and executions pin that, so editing a workflow can never change the
 * behaviour of a run already in flight.
 */
@Document(collection = "workflows")
public class Workflow {

    @Id
    private String id;

    @Indexed
    private String name;

    private String description;

    /** Version number the next publish will produce. Starts at 1. */
    private int version = 1;

    @Indexed
    private WorkflowStatus status = WorkflowStatus.DRAFT;

    private List<WorkflowNode> nodes = new ArrayList<>();
    private List<WorkflowConnection> connections = new ArrayList<>();

    /** Declared workflow-scope variables and their initial values. */
    private Map<String, Object> variables = new LinkedHashMap<>();

    private List<WorkflowTrigger> triggers = new ArrayList<>();

    /** Free-form labels, owner, tags. Not interpreted by the engine. */
    private Map<String, Object> metadata = new LinkedHashMap<>();

    private Instant createdAt;
    private Instant updatedAt;
    /**
     * User id of the owner, which is who may edit, delete and publish it besides an administrator.
     *
     * <p>Separate from {@link #createdBy}: the creator is history and never changes, while ownership can be
     * transferred when someone leaves or a workflow moves team. Null for a workflow created before
     * authentication existed, which {@code WorkflowAccessPolicy} adopts on its first authenticated edit.
     */
    @org.springframework.data.mongodb.core.index.Indexed
    private String ownerId;

    /**
     * Reserved for multi-tenancy, null today.
     *
     * <p>Carried now because retrofitting a tenant discriminator is far harder than an unused nullable
     * field: every query and every access check would need revisiting. {@code WorkflowAccessPolicy} is the
     * one place that would learn to use it.
     */
    @org.springframework.data.mongodb.core.index.Indexed
    private String tenantId;

    /**
     * Ids of the groups this workflow is shared with.
     *
     * <p>Ids only, never embedded group documents. A copied group would go stale the moment its permissions
     * changed, and permission changes have to take effect immediately: that is the entire reason group
     * permissions are not baked into the JWT either.
     *
     * <p>Empty means the workflow is reachable only by its owner and by administrators. Defaulting an
     * unshared workflow to "any user may view" would silently expose every workflow that existed before
     * groups were introduced.
     */
    @org.springframework.data.mongodb.core.index.Indexed
    private java.util.List<String> accessGroups = new java.util.ArrayList<>();

    /** Username of whoever first created this workflow. Historical; never reassigned. */
    private String createdBy;
    private String updatedBy;
    private Instant publishedAt;

    /** Version currently published and therefore executable; {@code null} when never published. */
    private Integer publishedVersion;

    @Version
    private Long documentVersion;

    public Workflow() {
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public java.util.List<String> getAccessGroups() {
        return accessGroups;
    }

    public void setAccessGroups(java.util.List<String> accessGroups) {
        this.accessGroups = accessGroups == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(accessGroups);
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Integer getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(Integer publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }
}
