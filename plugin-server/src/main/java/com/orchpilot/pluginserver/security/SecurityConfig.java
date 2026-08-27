package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.permission.PluginPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Who may call what.
 *
 * <h2>This registry authenticates its own users</h2>
 *
 * Tokens are minted by {@link JwtTokenService} against this registry's accounts and verified with this
 * registry's key. A token from the workflow platform is not accepted here, and that is the point: the registry
 * distributes executable code to every engine in the estate, so who may publish to it is a decision it makes
 * alone. Before this, a platform administrator was implicitly a registry administrator; they are now separate
 * jobs with separate credentials.
 *
 * <h2>Rules stated twice</h2>
 *
 * Coarse rules by path here, exact permissions on each controller method. The duplication is deliberate: the
 * path rules are a floor that catches an endpoint somebody forgot to annotate, and the annotations say what
 * each operation actually needs. Anything not named falls through to {@code authenticated()}, so a new endpoint
 * is protected by default rather than exposed by default.
 *
 * <h2>CSRF</h2>
 *
 * Disabled, because every authenticated request carries a bearer token in a header that a cross-site request
 * cannot set, and no endpoint here authenticates from a cookie. The refresh token is returned in the response
 * body rather than a cookie for the same reason: it keeps the API stateless and leaves CSRF with nothing to
 * attack. The trade is that the browser must hold the refresh token, which the console does in memory only.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Open without a token: the API description, liveness probes, and the JWKS this registry publishes. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/.well-known/jwks.json",
    };

    private final AuthProperties authProperties;
    private final JwtTokenService tokenService;

    public SecurityConfig(AuthProperties authProperties, JwtTokenService tokenService) {
        this.authProperties = authProperties;
        this.tokenService = tokenService;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()

                        // The only endpoints reachable without a token, and each of them exists to obtain
                        // one. Registration is listed here but refuses on its own when disabled, which it is
                        // by default: a registry is not a service people sign themselves up to.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/register", "/api/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/password-policy").permitAll()

                        // Publishing executable code: the narrowest rule in the file.
                        .requestMatchers(HttpMethod.POST, "/api/plugins/upload", "/api/plugins/validate")
                        .hasAuthority(PluginPermission.PLUGIN_UPLOAD.authority())

                        // What a workflow service does, and all it may do.
                        .requestMatchers(HttpMethod.GET, "/api/plugin-catalog")
                        .hasAuthority(PluginPermission.PLUGIN_READ.authority())
                        .requestMatchers(HttpMethod.GET, "/api/plugins/*/versions/*/download")
                        .hasAuthority(PluginPermission.PLUGIN_DOWNLOAD.authority())

                        .requestMatchers(HttpMethod.DELETE, "/api/plugins/**")
                        .hasAuthority(PluginPermission.PLUGIN_DELETE.authority())

                        // Lifecycle transitions, each named on its method as well.
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/activate")
                        .hasAuthority(PluginPermission.PLUGIN_ACTIVATE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/deactivate")
                        .hasAuthority(PluginPermission.PLUGIN_DEACTIVATE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/versions/*/publish")
                        .hasAuthority(PluginPermission.PLUGIN_ACTIVATE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/versions/*/deactivate")
                        .hasAuthority(PluginPermission.PLUGIN_DEACTIVATE.authority())
                        .requestMatchers(HttpMethod.POST,
                                "/api/plugins/*/versions/*/deprecate", "/api/plugins/*/versions/*/revoke")
                        .hasAuthority(PluginPermission.PLUGIN_DEPRECATE.authority())

                        .requestMatchers(HttpMethod.GET, "/api/plugins/*/versions/**")
                        .hasAnyAuthority(PluginPermission.PLUGIN_VERSION_READ.authority(),
                                PluginPermission.PLUGIN_READ.authority())
                        .requestMatchers(HttpMethod.GET, "/api/plugins/**")
                        .hasAuthority(PluginPermission.PLUGIN_READ.authority())

                        .requestMatchers("/api/plugin-audit/**")
                        .hasAuthority(PluginPermission.PLUGIN_AUDIT_READ.authority())
                        .requestMatchers("/api/security/audit/**")
                        .hasAuthority(PluginPermission.PLUGIN_AUDIT_READ.authority())

                        // Account administration. Read and write are separated so an auditor can be given
                        // sight of who exists without the ability to change it.
                        .requestMatchers(HttpMethod.GET, "/api/users/**")
                        .hasAuthority(PluginPermission.USER_READ.authority())
                        .requestMatchers(HttpMethod.POST, "/api/users")
                        .hasAuthority(PluginPermission.USER_CREATE.authority())
                        .requestMatchers(HttpMethod.DELETE, "/api/users/**")
                        .hasAuthority(PluginPermission.USER_DELETE.authority())
                        .requestMatchers("/api/users/**")
                        .hasAuthority(PluginPermission.USER_UPDATE.authority())

                        .requestMatchers(HttpMethod.GET, "/api/roles/**", "/api/permissions/**")
                        .hasAuthority(PluginPermission.ROLE_READ.authority())
                        .requestMatchers(HttpMethod.POST, "/api/roles")
                        .hasAuthority(PluginPermission.ROLE_CREATE.authority())
                        .requestMatchers(HttpMethod.DELETE, "/api/roles/**")
                        .hasAuthority(PluginPermission.ROLE_DELETE.authority())
                        .requestMatchers("/api/roles/**")
                        .hasAuthority(PluginPermission.ROLE_UPDATE.authority())

                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new PluginJwtAuthoritiesConverter())))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new PluginServerAuthenticationEntryPoint())
                        .accessDeniedHandler(new PluginServerAccessDeniedHandler()));

        return http.build();
    }

    /**
     * Verifies tokens with the key this registry signs them with.
     *
     * <p>The algorithm is pinned in both modes. A decoder that accepts whatever the token's header names is a
     * decoder that can be talked into {@code alg: none}, or into verifying an RSA signature with a public key
     * treated as an HMAC secret.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        if (tokenService.isAsymmetric()) {
            log.info("Verifying access tokens with RS256 against this registry's public key");
            return NimbusJwtDecoder.withPublicKey(tokenService.verificationKey()).build();
        }
        log.warn("Verifying access tokens with a shared HS256 secret. Anything holding it can also mint "
                + "tokens; configure an RSA key pair for anything beyond development.");
        return NimbusJwtDecoder.withSecretKey(tokenService.symmetricKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Which browser origins may call this API.
     *
     * <p>Named explicitly and never a wildcard. Credentials are allowed, and a wildcard with credentials would
     * mean every site the operator's browser visits can call this registry as them — which for a service that
     * publishes executable code is as bad as it sounds. Spring refuses that combination outright; naming the
     * console's origin is the correct answer rather than a workaround.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(authProperties.getAllowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        log.info("CORS allows {}", authProperties.getAllowedOrigins());
        return source;
    }
}
