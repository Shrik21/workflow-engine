package com.orchpilot.workflow.access;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named bundle of workflow permissions, held by a set of users and attachable to workflows.
 *
 * <p><b>Membership is not stored here.</b> It lives in {@code group_members} as one document per
 * user-and-group pair. The tradeoff, since embedding an id array is the obvious alternative:
 *
 * <ul>
 *   <li>An embedded array is bounded by MongoDB's 16 MB document limit, which sounds generous until a group
 *       is "all employees" and every membership change rewrites the whole document.</li>
 *   <li>Adding or removing a member becomes an atomic insert or delete rather than a read-modify-write on a
 *       shared document, so two administrators editing one group cannot lose each other's change.</li>
 *   <li>"Which groups is this user in" is the query the authorization path runs on <em>every</em> request. As
 *       a separate collection it is a single indexed lookup on {@code userId}; as an embedded array it is a
 *       scan of every group document.</li>
 *   <li>Each membership carries its own timestamp and author, so the audit trail can say who added whom and
 *       when, which an array of ids cannot express.</li>
 * </ul>
 *
 * <p>The cost is one extra query to count or list members, which happens on administration screens rather
 * than in the request path.
 */
@Document(collection = "groups")
public class Group {

    @Id
    private String id;

    /** Display name, unique so two groups cannot be confused in a picker. */
    @Indexed(unique = true)
    private String name;

    private String description;

    /**
     * The permissions this group grants on any workflow it is attached to.
     *
     * <p>Stored as an enum set, so a value that no longer exists in the code is dropped on read rather than
     * failing the query. An unrecognised permission granting nothing is the safe direction.
     */
    private Set<WorkflowPermission> permissions = new LinkedHashSet<>();

    /**
     * Disabling a group revokes the access it grants without deleting it or losing its membership.
     *
     * <p>The authorization path ignores disabled groups entirely, which makes this an immediate kill switch
     * for a group that was granted too much.
     */
    private boolean enabled = true;

    /** Reserved for multi-tenancy, as on {@code User} and {@code Workflow}. */
    @Indexed
    private String tenantId;

    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    @Version
    private Long documentVersion;

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

    public Set<WorkflowPermission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<WorkflowPermission> permissions) {
        this.permissions = permissions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(permissions);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(Long documentVersion) {
        this.documentVersion = documentVersion;
    }

    /**
     * @param permission the permission to test
     * @return whether this group grants it; always false when the group is disabled
     */
    public boolean grants(WorkflowPermission permission) {
        return enabled && permissions.contains(permission);
    }

    /** @return an immutable copy of the granted permissions */
    public Set<WorkflowPermission> permissionSet() {
        return permissions.isEmpty() ? EnumSet.noneOf(WorkflowPermission.class) : EnumSet.copyOf(permissions);
    }

    @Override
    public String toString() {
        return "Group{id=" + id + ", name=" + name + ", permissions=" + permissions.size()
                + ", enabled=" + enabled + "}";
    }
}
