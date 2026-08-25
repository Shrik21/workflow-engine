package com.orchpilot.pluginserver.permission;

import com.orchpilot.pluginserver.role.Role;
import com.orchpilot.pluginserver.role.RoleRepository;
import com.orchpilot.pluginserver.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How an account's permissions are worked out, and what the legacy names still mean.
 *
 * <h2>Why the legacy mapping is tested at all</h2>
 *
 * This registry had a different permission vocabulary before it had its own accounts, and service clients
 * registered under the old names are still in circulation — the workflow engine's among them. If
 * {@code PLUGIN_VIEW} stopped meaning anything, every engine syncing its catalogue would start failing with
 * 403 on the next restart, and the failure would look like a network problem.
 */
class PermissionResolutionTest {

    private RoleRepository roles;
    private PermissionService permissions;

    @BeforeEach
    void setUp() {
        roles = mock(RoleRepository.class);
        permissions = new PermissionService(roles);
        when(roles.findByName(anyString())).thenReturn(Optional.empty());
    }

    private void role(String name, PluginPermission... granted) {
        when(roles.findByName(name))
                .thenReturn(Optional.of(new Role(name, name, Set.of(granted), true)));
    }

    private static User userWith(String... roleNames) {
        User user = new User();
        user.setId("user-1");
        user.setUsername("someone");
        user.setRoles(new LinkedHashSet<>(java.util.Arrays.asList(roleNames)));
        return user;
    }

    @Nested
    @DisplayName("Resolution")
    class Resolution {

        @Test
        @DisplayName("permissions come from the account's roles")
        void fromRoles() {
            role(Role.PLUGIN_VIEWER, PluginPermission.PLUGIN_READ, PluginPermission.PLUGIN_DOWNLOAD);

            Set<PluginPermission> granted = permissions.getEffectivePermissions(userWith(Role.PLUGIN_VIEWER));

            assertEquals(Set.of(PluginPermission.PLUGIN_READ, PluginPermission.PLUGIN_DOWNLOAD), granted);
        }

        @Test
        @DisplayName("two roles union rather than one winning")
        void unionsRoles() {
            role(Role.PLUGIN_VIEWER, PluginPermission.PLUGIN_READ);
            role(Role.PLUGIN_AUDITOR, PluginPermission.PLUGIN_AUDIT_READ);

            Set<PluginPermission> granted =
                    permissions.getEffectivePermissions(userWith(Role.PLUGIN_VIEWER, Role.PLUGIN_AUDITOR));

            assertTrue(granted.contains(PluginPermission.PLUGIN_READ));
            assertTrue(granted.contains(PluginPermission.PLUGIN_AUDIT_READ));
        }

        @Test
        @DisplayName("a role that no longer exists grants nothing rather than failing")
        void missingRoleGrantsNothing() {
            // The account keeps working with less access. Throwing here would take down every request made by
            // anybody holding a role somebody deleted.
            assertTrue(permissions.getEffectivePermissions(userWith("ROLE_THAT_WAS_DELETED")).isEmpty());
        }

        @Test
        @DisplayName("permissions granted directly are added to those from roles")
        void directPermissions() {
            role(Role.PLUGIN_VIEWER, PluginPermission.PLUGIN_READ);
            User user = userWith(Role.PLUGIN_VIEWER);
            user.setDirectPermissions(Set.of(PluginPermission.PLUGIN_AUDIT_READ.name()));

            Set<PluginPermission> granted = permissions.getEffectivePermissions(user);

            assertTrue(granted.contains(PluginPermission.PLUGIN_READ));
            assertTrue(granted.contains(PluginPermission.PLUGIN_AUDIT_READ));
        }

        @Test
        @DisplayName("a viewer cannot upload, whatever else it holds")
        void viewerCannotUpload() {
            role(Role.PLUGIN_VIEWER, PluginPermission.PLUGIN_READ, PluginPermission.PLUGIN_VERSION_READ,
                    PluginPermission.PLUGIN_DOWNLOAD);

            assertFalse(permissions.hasPermission(userWith(Role.PLUGIN_VIEWER),
                    PluginPermission.PLUGIN_UPLOAD));
        }
    }

    @Nested
    @DisplayName("Legacy names")
    class Legacy {

        @Test
        @DisplayName("the old read authorities still mean read")
        void oldReadNames() {
            assertEquals(Set.of(PluginPermission.PLUGIN_READ), PluginPermission.expand("PLUGIN_VIEW"));
            assertEquals(Set.of(PluginPermission.PLUGIN_READ), PluginPermission.expand("PLUGIN_CATALOG_READ"));
        }

        @Test
        @DisplayName("one old lifecycle authority expands to all three it used to cover")
        void oldManageName() {
            // PLUGIN_VERSION_MANAGE was publish, deactivate and deprecate in one. Mapping it to a single
            // permission would quietly remove access a client already had.
            assertEquals(
                    Set.of(PluginPermission.PLUGIN_ACTIVATE, PluginPermission.PLUGIN_DEACTIVATE,
                            PluginPermission.PLUGIN_DEPRECATE),
                    PluginPermission.expand("PLUGIN_VERSION_MANAGE"));
        }

        @Test
        @DisplayName("a name that means nothing here expands to nothing rather than raising")
        void unknownName() {
            assertTrue(PluginPermission.expand("PLUGIN_INVENTED_IN_A_UI").isEmpty());
            assertTrue(PluginPermission.parse(null).isEmpty());
        }

        @Test
        @DisplayName("every permission is in a group, so a role editor can lay them out")
        void everyPermissionIsGrouped() {
            for (PluginPermission permission : PluginPermission.all()) {
                assertTrue(PluginPermission.inGroup(permission.group()).contains(permission));
                assertFalse(permission.description().isBlank());
            }
        }
    }
}
