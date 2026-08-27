package com.orchpilot.pluginserver.role;

import com.orchpilot.pluginserver.permission.PluginPermission;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named set of permissions.
 *
 * <h2>Why roles are data and permissions are code</h2>
 *
 * The permissions a role grants are stored, so an administrator can compose a role that suits how their
 * organisation actually divides the work. The permissions available to compose from are an enum, so every one
 * of them corresponds to a check that exists. That split is what keeps a role editor from producing something
 * that looks like access and grants nothing.
 *
 * <h2>System roles</h2>
 *
 * The four roles this registry ships with are marked {@code systemRole} and cannot be deleted. They can still
 * be edited, because an installation that wants its managers to also read the audit trail should not have to
 * clone a role to say so — but deleting the role every account depends on is a way to lock everybody out of a
 * running registry, and that is worth refusing.
 */
@Document(collection = "roles")
public class Role {

    /** Full administration, including users and roles. */
    public static final String PLUGIN_ADMIN = "PLUGIN_ADMIN";

    /** Everything about plugins; nothing about accounts. */
    public static final String PLUGIN_MANAGER = "PLUGIN_MANAGER";

    /** Read-only, plus download. */
    public static final String PLUGIN_VIEWER = "PLUGIN_VIEWER";

    /** Read-only, plus the audit trail. */
    public static final String PLUGIN_AUDITOR = "PLUGIN_AUDITOR";

    /** What a workflow service gets: read the catalogue, read versions, download archives. Nothing else. */
    public static final String PLUGIN_SERVICE = "PLUGIN_SERVICE";

    @Id
    private String id;

    /** Upper-snake-case, unique. This is the name that appears in a token's {@code roles} claim. */
    @Indexed(unique = true)
    private String name;

    private String description;

    /** Held by name rather than by reference: a permission is an enum constant, not a document. */
    private Set<String> permissions = new LinkedHashSet<>();

    /** Shipped with the registry. Editable, not deletable. */
    private boolean systemRole;

    private Instant createdAt;
    private Instant updatedAt;

    public Role() {
    }

    public Role(String name, String description, Set<PluginPermission> permissions, boolean systemRole) {
        this.name = name;
        this.description = description;
        this.systemRole = systemRole;
        setGranted(permissions);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(permissions);
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public void setSystemRole(boolean systemRole) {
        this.systemRole = systemRole;
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

    /**
     * Replaces the granted permissions.
     *
     * @param granted the permissions to grant
     */
    public final void setGranted(Set<PluginPermission> granted) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (granted != null) {
            for (PluginPermission permission : granted) {
                names.add(permission.name());
            }
        }
        this.permissions = names;
        this.updatedAt = Instant.now();
    }

    /**
     * The permissions this role grants, as enum constants.
     *
     * <p>Names that no longer correspond to a permission are dropped rather than raising. A role written
     * against a permission a later release removed should lose that one grant, not become unusable and take
     * every account holding it down with it.
     *
     * @return the resolved permissions
     */
    public Set<PluginPermission> granted() {
        LinkedHashSet<PluginPermission> resolved = new LinkedHashSet<>();
        for (String name : permissions) {
            resolved.addAll(PluginPermission.expand(name));
        }
        return resolved;
    }

    @Override
    public String toString() {
        return "Role{" + name + ", " + permissions.size() + " permission(s)}";
    }
}
