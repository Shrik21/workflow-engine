package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.auth.model.Permission;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The authenticated principal placed in the security context.
 *
 * <p>Carries the user id, which is what everything downstream actually needs: workflow ownership, the
 * execution context and the audit trail all key on it, and a username can be changed while an id cannot.
 *
 * <p>Authorities are the union of each role's {@code ROLE_} authority and every permission it grants, so
 * {@code hasRole('ADMIN')} and {@code hasAuthority('PLUGIN_UPLOAD')} both work. Prefer the permission form.
 *
 * <p>{@link #getPassword()} returns {@code null}. This principal is built after authentication has already
 * succeeded, so it has no reason to carry a credential, and a null keeps a password hash out of the
 * security context where it could be reached by anything holding the {@code Authentication}.
 */
public final class AuthPrincipal implements UserDetails {

    private static final long serialVersionUID = 1L;

    private final String userId;
    private final String username;
    private final String email;
    private final String displayName;
    private final Set<Role> roles;
    private final Set<Permission> permissions;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final boolean accountNonExpired;
    private final boolean credentialsNonExpired;
    private final String tenantId;

    private AuthPrincipal(User user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.displayName = displayNameOf(user);
        this.roles = Set.copyOf(user.getRoles());
        this.permissions = Set.copyOf(user.permissions());
        this.enabled = user.isEnabled();
        this.accountNonLocked = !user.isAccountLocked();
        this.accountNonExpired = !user.isAccountExpired();
        this.credentialsNonExpired = !user.isCredentialsExpired();
        this.tenantId = user.getTenantId();

        Set<GrantedAuthority> granted = new LinkedHashSet<>();
        for (Role role : this.roles) {
            granted.add(new SimpleGrantedAuthority(role.authority()));
        }
        for (Permission permission : this.permissions) {
            granted.add(new SimpleGrantedAuthority(permission.authority()));
        }
        this.authorities = List.copyOf(granted);
    }

    /**
     * @param user the loaded user
     * @return a principal carrying its identity and current authorities
     */
    public static AuthPrincipal of(User user) {
        return new AuthPrincipal(user);
    }

    private static String displayNameOf(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    /** @return the user id, which is the stable identity everything downstream keys on */
    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    /** @return the tenant this user belongs to, or {@code null} in a single-tenant deployment */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * @param permission the permission to test
     * @return whether this principal holds it
     */
    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    /** @return whether this principal holds the ADMIN role, which bypasses ownership checks */
    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * Always {@code null}: authentication has already happened, so there is nothing here to verify
     * against and no reason to hold a hash in the security context.
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return accountNonExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return credentialsNonExpired;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "AuthPrincipal{userId=" + userId + ", username=" + username + ", roles=" + roles + "}";
    }
}
