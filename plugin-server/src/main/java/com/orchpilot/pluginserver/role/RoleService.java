package com.orchpilot.pluginserver.role;

import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.permission.PluginPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The roles this registry ships with, and the management of any added to them.
 *
 * <h2>Seeded, then left alone</h2>
 *
 * The four system roles are created once, when they are absent. They are not rewritten on every start:
 * an installation that decided its managers should also read the audit trail has said something, and a
 * service that reset that on every deploy would be overruling the operator on a schedule.
 *
 * <p>The exception is a role that does not exist at all, which is created. That covers both a first start and
 * a later release adding a role.
 */
// Before InitialAdminInitializer, which creates an account that needs these roles to exist. An
// ApplicationRunner without an order runs at lowest precedence, so leaving this implicit put the seeding
// after the account that depends on it.
@Service
@Order(10)
public class RoleService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final RoleRepository roles;

    public RoleService(RoleRepository roles) {
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(Role.PLUGIN_ADMIN, "Full administration of the plugin registry, including accounts",
                PluginPermission.all());

        seed(Role.PLUGIN_MANAGER, "Publishes and manages plugins; cannot manage accounts",
                Set.of(PluginPermission.PLUGIN_READ,
                        PluginPermission.PLUGIN_UPLOAD,
                        PluginPermission.PLUGIN_VERSION_READ,
                        PluginPermission.PLUGIN_VERSION_CREATE,
                        PluginPermission.PLUGIN_DOWNLOAD,
                        PluginPermission.PLUGIN_ACTIVATE,
                        PluginPermission.PLUGIN_DEACTIVATE,
                        PluginPermission.PLUGIN_DEPRECATE,
                        PluginPermission.PLUGIN_AUDIT_READ,
                        PluginPermission.PLUGIN_USAGE_READ));

        seed(Role.PLUGIN_VIEWER, "Reads the catalogue and downloads archives",
                Set.of(PluginPermission.PLUGIN_READ,
                        PluginPermission.PLUGIN_VERSION_READ,
                        PluginPermission.PLUGIN_DOWNLOAD));

        seed(Role.PLUGIN_AUDITOR, "Reads the catalogue and the audit trail",
                Set.of(PluginPermission.PLUGIN_READ,
                        PluginPermission.PLUGIN_VERSION_READ,
                        PluginPermission.PLUGIN_AUDIT_READ));

        // What a workflow service gets. Read and download, nothing that changes anything: an engine consumes
        // this registry, and an engine compromised by a bad plugin must not be able to publish another.
        seed(Role.PLUGIN_SERVICE, "A workflow service: reads the catalogue and downloads archives",
                Set.of(PluginPermission.PLUGIN_READ,
                        PluginPermission.PLUGIN_VERSION_READ,
                        PluginPermission.PLUGIN_DOWNLOAD));
    }

    private void seed(String name, String description, Set<PluginPermission> permissions) {
        if (roles.existsByName(name)) {
            return;
        }
        roles.save(new Role(name, description, permissions, true));
        log.info("Created system role {} with {} permission(s)", name, permissions.size());
    }

    public List<Role> findAll() {
        return roles.findAllByOrderByNameAsc();
    }

    public Role require(String name) {
        return roles.findByName(name)
                .orElseThrow(() -> PluginServerException.notFound("ROLE_NOT_FOUND",
                        "There is no role named '" + name + "'."));
    }

    public Role requireById(String id) {
        return roles.findById(id)
                .orElseThrow(() -> PluginServerException.notFound("ROLE_NOT_FOUND",
                        "There is no role with id '" + id + "'."));
    }

    /**
     * Creates a role.
     *
     * @param name        upper-snake-case and unique
     * @param description what it is for
     * @param permissions what it grants; names that mean nothing here are refused rather than dropped
     * @return the stored role
     */
    public Role create(String name, String description, Set<String> permissions) {
        String normalised = normalise(name);
        if (roles.existsByName(normalised)) {
            throw PluginServerException.conflict("ROLE_EXISTS", "A role named '" + normalised + "' already exists.");
        }
        Role role = new Role(normalised, description, resolve(permissions), false);
        Role saved = roles.save(role);
        log.info("Created role {} with {} permission(s)", saved.getName(), saved.getPermissions().size());
        return saved;
    }

    /**
     * Replaces a role's description and permissions.
     *
     * <p>A system role may be edited. Denying that would force an installation to clone
     * {@code PLUGIN_MANAGER} to add one permission, and then maintain the clone.
     *
     * @param id          which role
     * @param description its new description
     * @param permissions its new permissions
     * @return the stored role
     */
    public Role update(String id, String description, Set<String> permissions) {
        Role role = requireById(id);
        role.setDescription(description);
        role.setGranted(resolve(permissions));
        role.setUpdatedAt(Instant.now());
        return roles.save(role);
    }

    /**
     * Deletes a role.
     *
     * <p>Refused for a system role. Deleting the role every account depends on is a way to lock everybody out
     * of a running registry, and no confirmation dialog makes that recoverable.
     *
     * @param id which role
     */
    public void delete(String id) {
        Role role = requireById(id);
        if (role.isSystemRole()) {
            throw PluginServerException.conflict("ROLE_PROTECTED",
                    "'" + role.getName() + "' is a system role and cannot be deleted. Edit its permissions "
                            + "instead, or stop assigning it.");
        }
        roles.delete(role);
        log.info("Deleted role {}", role.getName());
    }

    /** Refuses unknown names rather than dropping them: a role that silently grants less is worse than an error. */
    private Set<PluginPermission> resolve(Set<String> names) {
        LinkedHashSet<PluginPermission> resolved = new LinkedHashSet<>();
        if (names == null) {
            return resolved;
        }
        for (String name : names) {
            Set<PluginPermission> expanded = PluginPermission.expand(name);
            if (expanded.isEmpty()) {
                throw PluginServerException.badRequest("PERMISSION_UNKNOWN",
                        "'" + name + "' is not a permission this registry recognises.");
            }
            resolved.addAll(expanded);
        }
        return resolved;
    }

    private static String normalise(String name) {
        if (name == null || name.isBlank()) {
            throw PluginServerException.badRequest("ROLE_INVALID", "A role must have a name.");
        }
        String trimmed = name.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!trimmed.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw PluginServerException.badRequest("ROLE_INVALID",
                    "A role name must be 3 to 64 characters of upper-case letters, digits and underscores.");
        }
        return trimmed;
    }
}
