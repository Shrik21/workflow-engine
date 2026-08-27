package com.orchpilot.pluginserver.permission;

import com.orchpilot.pluginserver.role.Role;
import com.orchpilot.pluginserver.role.RoleRepository;
import com.orchpilot.pluginserver.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What an account may actually do.
 *
 * <h2>Resolved from roles, not stored on the account</h2>
 *
 * A user holds role names; a role holds permissions. Effective permissions are the union, computed here. The
 * alternative — writing the permissions onto each account — makes changing a role a migration over every user
 * who holds it, and guarantees that some of them are missed.
 *
 * <h2>Where the result is used</h2>
 *
 * Once, at sign-in, to fill the token's {@code permissions} claim. Every request after that is authorised from
 * the token without touching the database. The cost is that a permission taken away stays effective until the
 * token expires, which is why access tokens are short-lived; the benefit is that authorisation does not add a
 * database read to every request to a service whose job is serving large files.
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final RoleRepository roles;

    public PermissionService(RoleRepository roles) {
        this.roles = roles;
    }

    /**
     * Everything this account may do.
     *
     * @param user the account
     * @return the union of its roles' permissions and any granted to it directly
     */
    public Set<PluginPermission> getEffectivePermissions(User user) {
        LinkedHashSet<PluginPermission> effective = new LinkedHashSet<>();
        if (user == null) {
            return effective;
        }

        for (String roleName : user.getRoles()) {
            Role role = roles.findByName(roleName).orElse(null);
            if (role == null) {
                // A role that no longer exists grants nothing. Logged rather than ignored: it means an
                // account is carrying a name somebody deleted, and that account now has less access than
                // whoever assigned it intended.
                log.warn("User '{}' holds role '{}', which does not exist; it grants nothing",
                        user.getUsername(), roleName);
                continue;
            }
            effective.addAll(role.granted());
        }

        for (String direct : user.getDirectPermissions()) {
            effective.addAll(PluginPermission.expand(direct));
        }
        return effective;
    }

    /**
     * @param user the account
     * @return its effective permissions as the strings a token claim and Spring Security use
     */
    public Set<String> getEffectivePermissionNames(User user) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (PluginPermission permission : getEffectivePermissions(user)) {
            names.add(permission.name());
        }
        return names;
    }

    /**
     * @param user       the account
     * @param permission the permission to test
     * @return whether the account holds it
     */
    public boolean hasPermission(User user, PluginPermission permission) {
        return getEffectivePermissions(user).contains(permission);
    }
}
