package com.orchpilot.pluginserver.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a token is allowed to grant.
 *
 * <h2>The behaviour these tests changed</h2>
 *
 * An earlier version mapped workflow-platform roles onto registry permissions, so a platform administrator was
 * implicitly a registry administrator. That is the dependency this module removed: the registry distributes
 * executable code and decides for itself who may publish to it. The first test below is the one that pins it —
 * a token carrying platform roles and none of this registry's claims must grant nothing at all.
 */
class PluginJwtAuthoritiesConverterTest {

    private final PluginJwtAuthoritiesConverter converter = new PluginJwtAuthoritiesConverter();

    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .subject("user-1");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private Set<String> authoritiesOf(Map<String, Object> claims) {
        AbstractAuthenticationToken authentication = converter.convert(token(claims));
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("Independence")
    class Independence {

        @Test
        @DisplayName("a workflow-platform token grants nothing here")
        void platformTokenGrantsNothing() {
            // The shape a platform token has: roles the workflow service understands, and none of this
            // registry's permission claims. It must not become registry access.
            Set<String> granted = authoritiesOf(Map.of(
                    "roles", List.of("ADMIN", "WORKFLOW_ADMIN"),
                    "username", "platform-admin"));

            assertFalse(granted.contains("PLUGIN_UPLOAD"));
            assertFalse(granted.contains("PLUGIN_DELETE"));
            assertFalse(granted.contains("PLUGIN_READ"));
            // The role itself is still present for hasRole(), but it carries no permission.
            assertTrue(granted.contains("ROLE_ADMIN"));
        }

        @Test
        @DisplayName("a token with no usable claims is authenticated but powerless")
        void noClaimsMeansNoAuthority() {
            assertTrue(authoritiesOf(Map.of("username", "nobody")).isEmpty());
        }
    }

    @Nested
    @DisplayName("This registry's own claims")
    class OwnClaims {

        @Test
        @DisplayName("permissions the registry issued are honoured")
        void readsPermissions() {
            Set<String> granted = authoritiesOf(Map.of(
                    "permissions", List.of("PLUGIN_READ", "PLUGIN_UPLOAD"),
                    "roles", List.of("PLUGIN_MANAGER"),
                    "username", "manager"));

            assertTrue(granted.contains("PLUGIN_READ"));
            assertTrue(granted.contains("PLUGIN_UPLOAD"));
            assertTrue(granted.contains("ROLE_PLUGIN_MANAGER"));
        }

        @Test
        @DisplayName("an unknown permission name is ignored rather than refused")
        void ignoresUnknownPermissions() {
            // A token from an older release naming a permission since renamed should lose that one grant,
            // not fail to authenticate at all.
            Set<String> granted = authoritiesOf(Map.of(
                    "permissions", List.of("PLUGIN_READ", "PLUGIN_INVENTED"), "username", "someone"));

            assertTrue(granted.contains("PLUGIN_READ"));
            assertEquals(1, granted.size());
        }

        @Test
        @DisplayName("a space-delimited scope is read as well as a list, for service tokens")
        void readsSpaceDelimitedScope() {
            Set<String> granted = authoritiesOf(Map.of(
                    "scope", "PLUGIN_READ PLUGIN_DOWNLOAD", "username", "workflow-service"));

            assertEquals(Set.of("PLUGIN_READ", "PLUGIN_DOWNLOAD"), granted);
        }

        @Test
        @DisplayName("a service client registered under the old names keeps its access")
        void legacyServiceClient() {
            // The workflow engine's client was registered before this registry had its own vocabulary.
            // Losing this mapping would 403 every catalogue sync in the estate.
            Set<String> granted = authoritiesOf(Map.of(
                    "scope", "PLUGIN_VIEW PLUGIN_CATALOG_READ PLUGIN_DOWNLOAD",
                    "username", "workflow-service"));

            assertTrue(granted.contains("PLUGIN_READ"));
            assertTrue(granted.contains("PLUGIN_DOWNLOAD"));
            assertFalse(granted.contains("PLUGIN_UPLOAD"), "a consumer must never gain the right to publish");
        }

        @Test
        @DisplayName("a viewer cannot upload or delete")
        void viewerIsReadOnly() {
            Set<String> granted = authoritiesOf(Map.of(
                    "permissions", List.of("PLUGIN_READ", "PLUGIN_VERSION_READ", "PLUGIN_DOWNLOAD"),
                    "roles", List.of("PLUGIN_VIEWER"), "username", "viewer"));

            assertFalse(granted.contains("PLUGIN_UPLOAD"));
            assertFalse(granted.contains("PLUGIN_DELETE"));
            assertFalse(granted.contains("USER_READ"));
        }
    }

    @Test
    @DisplayName("actions are attributed to the username when there is one, the subject otherwise")
    void namesTheActor() {
        assertEquals("someone", converter.convert(token(Map.of("username", "someone"))).getName());
        assertEquals("user-1", converter.convert(token(Map.of())).getName());
    }
}
