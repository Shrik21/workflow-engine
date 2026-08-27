package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.permission.PluginPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a token into the authorities a request is authorised against.
 *
 * <h2>Only this registry's own tokens</h2>
 *
 * An earlier version mapped workflow-platform roles onto registry permissions, so a platform administrator was
 * implicitly a registry administrator. That is exactly the dependency this module exists to remove: the
 * registry distributes executable code, and it must decide who may publish to it without deferring that
 * decision to another service's user database. Only the {@code permissions} and {@code roles} claims this
 * registry writes are read now, and a token signed by anything else fails verification long before it reaches
 * here.
 *
 * <h2>Two kinds of authority</h2>
 *
 * Permissions become plain authorities, which is what {@code hasAuthority('PLUGIN_UPLOAD')} checks. Roles
 * additionally become {@code ROLE_}-prefixed authorities, so {@code hasRole('PLUGIN_ADMIN')} works for the
 * handful of places a coarse check reads better. Permission checks are preferred everywhere else: they survive
 * somebody deciding their managers should also read the audit trail.
 */
public class PluginJwtAuthoritiesConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(PluginJwtAuthoritiesConverter.class);

    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_PERMISSIONS = "permissions";
    /** Space-delimited, as the client-credentials convention expects. Read for service tokens. */
    static final String CLAIM_SCOPE = "scope";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> granted = new LinkedHashSet<>();

        for (PluginPermission permission : permissionsFrom(jwt)) {
            granted.add(new SimpleGrantedAuthority(permission.authority()));
        }
        for (String role : stringsFrom(jwt, CLAIM_ROLES)) {
            granted.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase(java.util.Locale.ROOT)));
        }

        if (granted.isEmpty()) {
            // Authenticated and able to do nothing. Worth a line: it usually means a role was deleted, or a
            // service client is registered with no authorities.
            log.debug("Token for '{}' carries no authority this registry recognises", jwt.getSubject());
        }
        return new JwtAuthenticationToken(jwt, granted, nameOf(jwt));
    }

    /**
     * The permissions a token names.
     *
     * <p>Both claims are read: {@code permissions} for a person's token, {@code scope} for a service token,
     * which follows the client-credentials convention of a space-delimited string. Names this registry does
     * not recognise are ignored rather than refused — a token from an older release naming a permission that
     * has since been renamed should lose that one grant, not fail to authenticate at all.
     */
    private static Set<PluginPermission> permissionsFrom(Jwt jwt) {
        Set<PluginPermission> found = new LinkedHashSet<>();
        for (String claim : List.of(CLAIM_PERMISSIONS, CLAIM_SCOPE)) {
            for (String value : stringsFrom(jwt, claim)) {
                found.addAll(PluginPermission.expand(value));
            }
        }
        return found;
    }

    /** Reads a claim that may be a list or a space-delimited string, which {@code scope} is. */
    private static List<String> stringsFrom(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        if (value instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object item : collection) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim().split("\\s+"));
        }
        return List.of();
    }

    /**
     * What the request is attributed to.
     *
     * <p>The username where there is one, falling back to the subject. The subject of a person's token is
     * their account id, which is stable but unreadable in an audit row.
     */
    private static String nameOf(Jwt jwt) {
        String username = jwt.getClaimAsString(JwtTokenService.CLAIM_USERNAME);
        return username != null && !username.isBlank() ? username : jwt.getSubject();
    }
}
