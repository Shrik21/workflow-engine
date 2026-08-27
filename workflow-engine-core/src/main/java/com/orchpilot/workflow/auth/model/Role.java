package com.orchpilot.workflow.auth.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A named bundle of {@link Permission}s.
 *
 * <p>{@code ADMIN} and {@code USER} are the operative roles. The remaining constants exist because
 * the platform was asked to make adding roles cheap, and the cheapest possible demonstration is that
 * they already work: each is a real role, assignable by an administrator, that needs no change
 * anywhere else in the code base to take effect. Nothing outside this enum knows which permissions a
 * role carries.
 *
 * <p>A role is granted as the authority {@code ROLE_<name>} in addition to each of its permissions,
 * so both {@code hasRole('ADMIN')} and {@code hasAuthority('PLUGIN_UPLOAD')} work. Prefer the
 * permission form: it survives the introduction of a new role, and the role form does not.
 */
public enum Role {

    /** Everything. The only role that may manage users, plugins and secrets. */
    ADMIN(EnumSet.allOf(Permission.class)),

    /**
     * The default for self-registration.
     *
     * <p>Deliberately excludes user management, plugin management, secrets and publishing. A USER can
     * build and run their own workflows, which is the whole intent, and can neither install
     * executable code into the engine nor read a credential.
     */
    USER(EnumSet.of(
            Permission.WORKFLOW_VIEW,
            Permission.WORKFLOW_CREATE,
            Permission.WORKFLOW_EDIT,
            Permission.WORKFLOW_EXECUTE,
            Permission.EXECUTION_VIEW,
            // Participating in a workflow is the ordinary case, not a privilege: a user who cannot answer the
            // tasks addressed to them cannot use a workflow that contains a form node at all.
            Permission.TASK_VIEW,
            Permission.TASK_CLAIM,
            Permission.TASK_COMPLETE,
            Permission.AI_PROVIDER_VIEW)),

    /** Full authority over workflows and their executions, but not over users or plugins. */
    WORKFLOW_ADMIN(EnumSet.of(
            Permission.WORKFLOW_VIEW,
            Permission.WORKFLOW_CREATE,
            Permission.WORKFLOW_EDIT,
            Permission.WORKFLOW_DELETE,
            Permission.WORKFLOW_EXECUTE,
            Permission.WORKFLOW_PUBLISH,
            Permission.EXECUTION_VIEW,
            Permission.EXECUTION_CANCEL,
            Permission.WORKFLOW_INSTANCE_PAUSE,
            Permission.WORKFLOW_INSTANCE_RESUME,
            Permission.WORKFLOW_INSTANCE_TERMINATE,
            Permission.EXTERNAL_FORM_CREATE_LINK,
            Permission.EXTERNAL_FORM_REVOKE_LINK,
            Permission.AI_PROVIDER_VIEW,
            Permission.AI_PROVIDER_MANAGE,
            Permission.SCHEDULE_VIEW,
            Permission.SCHEDULE_CREATE,
            Permission.SCHEDULE_EDIT,
            Permission.SCHEDULE_DELETE,
            Permission.EVENT_EMIT,
            Permission.PLUGIN_VIEW,
            Permission.TASK_VIEW,
            Permission.TASK_VIEW_ALL,
            Permission.TASK_CLAIM,
            Permission.TASK_COMPLETE,
            Permission.TASK_CANCEL,
            Permission.TASK_REASSIGN,
            Permission.TASK_ADMIN)),

    /** Builds and runs workflows but cannot publish them, so a reviewer stays in the loop. */
    WORKFLOW_EDITOR(EnumSet.of(
            Permission.WORKFLOW_VIEW,
            Permission.WORKFLOW_CREATE,
            Permission.WORKFLOW_EDIT,
            Permission.WORKFLOW_EXECUTE,
            Permission.EXECUTION_VIEW,
            Permission.PLUGIN_VIEW,
            Permission.TASK_VIEW,
            Permission.TASK_CLAIM,
            Permission.TASK_COMPLETE)),

    /**
     * Read-only. Useful for auditors and for dashboards.
     *
     * <p>{@code TASK_VIEW_ALL} without {@code TASK_VIEW} is deliberate and is the one place the distinction
     * earns its keep: an auditor may read every task and act on none.
     */
    WORKFLOW_VIEWER(EnumSet.of(
            Permission.WORKFLOW_VIEW,
            Permission.EXECUTION_VIEW,
            Permission.SCHEDULE_VIEW,
            Permission.PLUGIN_VIEW,
            Permission.TASK_VIEW,
            Permission.TASK_VIEW_ALL)),

    /** Manages the plugin platform without gaining access to workflows or users. */
    PLUGIN_ADMIN(EnumSet.of(
            Permission.PLUGIN_VIEW,
            Permission.PLUGIN_UPLOAD,
            Permission.PLUGIN_ACTIVATE,
            Permission.PLUGIN_DEACTIVATE,
            Permission.PLUGIN_DELETE,
            Permission.SECRET_VIEW,
            Permission.SECRET_MANAGE)),

    /** Operates running work: watch, cancel, resume. Cannot change a definition. */
    EXECUTION_ADMIN(EnumSet.of(
            Permission.WORKFLOW_VIEW,
            Permission.EXECUTION_VIEW,
            Permission.EXECUTION_CANCEL,
            Permission.SCHEDULE_VIEW,
            Permission.EVENT_EMIT,
            Permission.TASK_VIEW,
            Permission.TASK_VIEW_ALL,
            Permission.TASK_CANCEL,
            Permission.TASK_REASSIGN,
            Permission.TASK_ADMIN));

    /** Prefix Spring Security expects on a role authority, as opposed to a permission authority. */
    public static final String AUTHORITY_PREFIX = "ROLE_";

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    /**
     * @return the permissions this role grants, never modifiable
     */
    public Set<Permission> permissions() {
        return permissions;
    }

    /**
     * @return the authority name for the role itself, for example {@code ROLE_ADMIN}
     */
    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    /**
     * @param permission the permission to test
     * @return whether this role grants it
     */
    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }

    /**
     * Parses a role name defensively.
     *
     * <p>Returns empty rather than throwing, because the input is sometimes a value read back from
     * the database that was written by an older version of the application. An unrecognised role is
     * dropped, which fails closed: the user simply does not receive its authorities.
     *
     * @param value candidate role name, case-insensitive; may be {@code null}
     * @return the role, or empty when the name is unknown
     */
    public static java.util.Optional<Role> parse(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT);
        final String normalised = upper.startsWith(AUTHORITY_PREFIX)
                ? upper.substring(AUTHORITY_PREFIX.length())
                : upper;
        return Arrays.stream(values()).filter(role -> role.name().equals(normalised)).findFirst();
    }
}
