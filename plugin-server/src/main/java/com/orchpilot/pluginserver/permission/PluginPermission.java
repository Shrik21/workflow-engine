package com.orchpilot.pluginserver.permission;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Every permission this registry recognises.
 *
 * <h2>Closed, not open</h2>
 *
 * An enum rather than a collection somebody can add rows to. A permission is a name the code checks against, so
 * one that no {@code @PreAuthorize} mentions grants nothing however convincing it looks in a role editor, and
 * one invented by a typo silently grants nothing to whoever was given it. Roles are data and are edited freely;
 * the vocabulary they draw on is code, and adding to it is a deliberate change with a matching check.
 *
 * <h2>Grouped for the people who assign them</h2>
 *
 * The {@link Group} exists so a role editor can present these as "plugins", "users", "security" rather than as
 * an alphabetical wall of constants. Grouping is presentation; it grants nothing and implies nothing.
 */
public enum PluginPermission {

    // ------------------------------------------------------------------ plugins

    /** See plugins, their metadata, their nodes and the catalogue. */
    PLUGIN_READ(Group.PLUGINS, "Read plugins and the catalogue"),

    /** Publish an archive. The most privileged plugin permission: it distributes executable code. */
    PLUGIN_UPLOAD(Group.PLUGINS, "Upload plugin archives"),

    /** See the versions of a plugin. */
    PLUGIN_VERSION_READ(Group.PLUGINS, "Read plugin versions"),

    /** Add a version to an existing plugin. */
    PLUGIN_VERSION_CREATE(Group.PLUGINS, "Create plugin versions"),

    /** Download an archive. Held by workflow services, which install what they download. */
    PLUGIN_DOWNLOAD(Group.PLUGINS, "Download plugin archives"),

    PLUGIN_ACTIVATE(Group.PLUGINS, "Activate plugins and publish versions"),

    PLUGIN_DEACTIVATE(Group.PLUGINS, "Deactivate plugins and versions"),

    PLUGIN_DEPRECATE(Group.PLUGINS, "Deprecate and revoke versions"),

    /** Remove a plugin or version outright. */
    PLUGIN_DELETE(Group.PLUGINS, "Delete plugins and versions"),

    // -------------------------------------------------------------------- audit

    PLUGIN_AUDIT_READ(Group.AUDIT, "Read the audit trail"),

    PLUGIN_USAGE_READ(Group.AUDIT, "Read plugin usage information"),

    // ----------------------------------------------------------- administration

    USER_READ(Group.USERS, "Read user accounts"),
    USER_CREATE(Group.USERS, "Create user accounts"),
    USER_UPDATE(Group.USERS, "Update user accounts"),
    USER_DELETE(Group.USERS, "Delete user accounts"),

    ROLE_READ(Group.ROLES, "Read roles"),
    ROLE_CREATE(Group.ROLES, "Create roles"),
    ROLE_UPDATE(Group.ROLES, "Update roles"),
    ROLE_DELETE(Group.ROLES, "Delete roles");

    /** How a role editor groups these for a human. Presentational only. */
    public enum Group {
        PLUGINS("Plugins"),
        AUDIT("Audit and usage"),
        USERS("User management"),
        ROLES("Role management");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Group group;
    private final String description;

    PluginPermission(Group group, String description) {
        this.group = group;
        this.description = description;
    }

    public Group group() {
        return group;
    }

    public String description() {
        return description;
    }

    /**
     * @return the string Spring Security compares against, which is the enum name
     */
    public String authority() {
        return name();
    }

    /**
     * Reads a permission name, tolerating the names this registry used before it had its own accounts.
     *
     * <p>The older vocabulary is still present in two places that must keep working: service clients
     * registered before this change, and any deployment whose configuration names them. Rather than migrate
     * those and risk locking out a workflow service mid-upgrade, the old names are accepted and mapped here,
     * in one place, where the mapping can be read and eventually removed.
     *
     * @param value a permission name, current or legacy
     * @return the permission, or empty when the name means nothing here
     */
    public static Optional<PluginPermission> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        return switch (name) {
            // Legacy names, from before the registry had its own users.
            case "PLUGIN_VIEW", "PLUGIN_CATALOG_READ" -> Optional.of(PLUGIN_READ);
            case "PLUGIN_VERSION_MANAGE" -> Optional.of(PLUGIN_ACTIVATE);
            case "PLUGIN_ADMIN" -> Optional.empty();
            default -> Arrays.stream(values()).filter(candidate -> candidate.name().equals(name)).findFirst();
        };
    }

    /**
     * Expands a legacy authority into every current permission it used to imply.
     *
     * <p>{@code PLUGIN_VERSION_MANAGE} was one authority covering publish, deactivate and deprecate, which are
     * three permissions here. Mapping it to only one would quietly remove access a service client already had.
     *
     * @param value a permission name, current or legacy
     * @return the permissions it grants
     */
    public static Set<PluginPermission> expand(String value) {
        if (value == null) {
            return Set.of();
        }
        String name = value.trim().toUpperCase(Locale.ROOT);
        return switch (name) {
            case "PLUGIN_VERSION_MANAGE" -> Set.of(PLUGIN_ACTIVATE, PLUGIN_DEACTIVATE, PLUGIN_DEPRECATE);
            case "PLUGIN_ADMIN" -> all();
            default -> parse(name).map(Set::of).orElse(Set.of());
        };
    }

    /** @return every permission, for an administrator's role and for the permission catalogue endpoint */
    public static Set<PluginPermission> all() {
        return new LinkedHashSet<>(Arrays.asList(values()));
    }

    /**
     * @param group the group
     * @return the permissions in it, in declaration order
     */
    public static Set<PluginPermission> inGroup(Group group) {
        LinkedHashSet<PluginPermission> found = new LinkedHashSet<>();
        for (PluginPermission permission : values()) {
            if (permission.group == group) {
                found.add(permission);
            }
        }
        return found;
    }
}
