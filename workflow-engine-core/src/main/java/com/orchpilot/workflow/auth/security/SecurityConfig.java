package com.orchpilot.workflow.auth.security;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.model.Permission;
import com.orchpilot.workflow.auth.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.HashMap;
import java.util.Map;

/**
 * The single place every authorization rule lives.
 *
 * <p>Rules are declared as path and method patterns here rather than as annotations spread over the
 * controllers, so the whole policy can be read in one screen and reviewed as a unit. Adding an endpoint
 * without a rule leaves it caught by the final {@code anyRequest().authenticated()}, which fails closed.
 *
 * <p>Every rule asserts a {@link Permission}, not a {@link Role}. Introducing a role later is then a
 * change to the role's permission set and nothing else. The one exception is {@code /api/admin/**}, which
 * additionally requires the ADMIN role, because user administration is the one area where the coarse
 * check is genuinely what is meant.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Endpoints reachable without authentication. Deliberately short and individually justified. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/login",
            "/api/auth/register",
            // Refresh is public because by definition the caller has no valid access token; the refresh
            // token itself is the credential and is verified inside the endpoint.
            "/api/auth/refresh",
            "/api/auth/logout",
            // Lets the registration form show the real rules instead of hardcoding a guess at them.
            "/api/auth/password-policy",
            // External (public) form links. Authorised by a secure single-purpose form token, not a JWT: an
            // external customer has no OrchPilot account. The token is verified inside every one of these
            // endpoints, which resolve the task, instance and tenant from it and never from the request, so a
            // token can reach exactly one task and cannot call any internal API.
            "/api/public/forms/**",
            // Liveness and readiness only. The remaining actuator endpoints require authentication.
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            // Public by necessity when RS256 is in use: it publishes the verification key, which is
            // meant to be public. Absent under HS256, where no such endpoint exists.
            "/.well-known/jwks.json",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
    };

    private final JwtAuthenticationFilter jwtFilter;
    private final SecurityErrorResponder errorResponder;
    private final AuthProperties properties;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, SecurityErrorResponder errorResponder,
                          AuthProperties properties) {
        this.jwtFilter = jwtFilter;
        this.errorResponder = errorResponder;
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS is configured by CorsConfig's CorsConfigurationSource and applied here, inside the
                // security chain, so a preflight is answered before authentication is considered.
                .cors(Customizer.withDefaults())

                /*
                 * CSRF tokens are disabled because API authentication is a bearer token in a header, which
                 * a cross-site form post cannot set. The one cookie the platform issues is the refresh
                 * token, and it is defended differently and deliberately: SameSite=Strict, so a browser
                 * will not attach it to a cross-site request at all, and Path=/api/auth, so even a
                 * same-site request cannot aim it at any other endpoint. Switching
                 * security.jwt.refresh-token-transport to "body" removes the cookie entirely.
                 */
                .csrf(csrf -> csrf.disable())

                // No server-side session: nothing to fixate, and nothing to replicate between instances.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Both responses are deliberately generic. See SecurityErrorResponder.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder))

                .headers(headers -> headers
                        // The API serves JSON, never a document that should frame or be framed.
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        /*
                         * A restrictive policy for API responses. There is no unsafe-inline or unsafe-eval,
                         * and default-src is 'none' because a JSON response has no legitimate reason to
                         * load anything. The console is served by nginx, which sets its own policy suited
                         * to an application shell.
                         */
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                        // Only meaningful over HTTPS; Spring Security omits it for plain HTTP requests.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))

                .authorizeHttpRequests(auth -> auth
                        // Preflight carries no credentials and is answered by the CORS layer.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()

                        // ---------------------------------------------------------------- workflows
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/publish")
                        .hasAuthority(Permission.WORKFLOW_PUBLISH.authority())
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/execute")
                        .hasAuthority(Permission.WORKFLOW_EXECUTE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/validate")
                        .hasAuthority(Permission.WORKFLOW_VIEW.authority())
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/archive")
                        .hasAuthority(Permission.WORKFLOW_EDIT.authority())
                        // Bulk control over a workflow's in-flight executions is an execution operation,
                        // not a definition one, so it takes the execution permission.
                        .requestMatchers(HttpMethod.POST, "/api/workflows/*/pause",
                                "/api/workflows/*/resume", "/api/workflows/*/cancel")
                        .hasAuthority(Permission.EXECUTION_CANCEL.authority())
                        .requestMatchers(HttpMethod.GET, "/api/workflows", "/api/workflows/**")
                        .hasAuthority(Permission.WORKFLOW_VIEW.authority())
                        .requestMatchers(HttpMethod.POST, "/api/workflows")
                        .hasAuthority(Permission.WORKFLOW_CREATE.authority())
                        .requestMatchers(HttpMethod.PUT, "/api/workflows/**")
                        .hasAuthority(Permission.WORKFLOW_EDIT.authority())
                        .requestMatchers(HttpMethod.DELETE, "/api/workflows/**")
                        .hasAuthority(Permission.WORKFLOW_DELETE.authority())

                        // --------------------------------------------------------------- executions
                        // Submitting a form is participating in a workflow, so it takes the execute
                        // permission rather than a management one: a USER must be able to answer a task.
                        .requestMatchers(HttpMethod.POST, "/api/executions/*/form")
                        .hasAuthority(Permission.WORKFLOW_EXECUTE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/executions/*/cancel",
                                "/api/executions/*/pause", "/api/executions/*/resume")
                        .hasAuthority(Permission.EXECUTION_CANCEL.authority())
                        .requestMatchers(HttpMethod.GET, "/api/executions/**")
                        .hasAuthority(Permission.EXECUTION_VIEW.authority())

                        // ------------------------------------------------------------------ plugins
                        // Uploading a plugin runs arbitrary code inside the engine's JVM. It is the most
                        // privileged operation the platform offers and is ADMIN-only by permission.
                        // Refreshing the catalogue reads another service and writes only a cache. PLUGIN_VIEW,
                        // not an administrative permission: somebody looking at a stale marketplace should not
                        // have to find an administrator to press refresh.
                        .requestMatchers(HttpMethod.POST, "/api/plugins/sync")
                        .hasAuthority(Permission.PLUGIN_VIEW.authority())

                        .requestMatchers(HttpMethod.POST, "/api/plugins/upload")
                        .hasAuthority(Permission.PLUGIN_UPLOAD.authority())

                        // Changing a version's allowed hosts widens what it can reach, which is exactly the
                        // power an upload grants, so it takes the upload permission rather than the lesser
                        // activate one. Granting a plugin a host is as privileged as installing it.
                        .requestMatchers(HttpMethod.PUT, "/api/plugins/*/permissions")
                        .hasAuthority(Permission.PLUGIN_UPLOAD.authority())

                        // Installing from the registry ends the same way an upload does, with third-party
                        // code running inside this JVM, so it takes the same permission. The registry is a
                        // distribution channel, not a review board.
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/install",
                                "/api/plugins/*/versions/*/install", "/api/plugins/*/update")
                        .hasAuthority(Permission.PLUGIN_UPLOAD.authority())
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/versions/*/activate")
                        .hasAuthority(Permission.PLUGIN_ACTIVATE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/versions/*/deactivate")
                        .hasAuthority(Permission.PLUGIN_DEACTIVATE.authority())

                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/activate")
                        .hasAuthority(Permission.PLUGIN_ACTIVATE.authority())
                        .requestMatchers(HttpMethod.POST, "/api/plugins/*/deactivate",
                                "/api/plugins/*/reload", "/api/plugins/*/default-version")
                        .hasAuthority(Permission.PLUGIN_DEACTIVATE.authority())
                        .requestMatchers(HttpMethod.DELETE, "/api/plugins/**")
                        .hasAuthority(Permission.PLUGIN_DELETE.authority())
                        .requestMatchers(HttpMethod.GET, "/api/plugins/**")
                        .hasAuthority(Permission.PLUGIN_VIEW.authority())

                        // ------------------------------------------------------------------ secrets
                        .requestMatchers(HttpMethod.GET, "/api/secrets/**")
                        .hasAuthority(Permission.SECRET_VIEW.authority())
                        .requestMatchers("/api/secrets/**")
                        .hasAuthority(Permission.SECRET_MANAGE.authority())

                        // ------------------------------------------------------------------- events
                        .requestMatchers(HttpMethod.POST, "/api/events")
                        .hasAuthority(Permission.EVENT_EMIT.authority())

                        // -------------------------------------------------------------------- forms
                        // A form exists to serve a workflow, so it reuses the workflow permissions rather
                        // than introducing a parallel set: one capability, one thing to configure. The field
                        // catalogue is open to any authenticated user because the designer renders from it.
                        .requestMatchers(HttpMethod.GET, "/api/forms/field-types").authenticated()
                        .requestMatchers("/api/forms/**").authenticated()

                        // -------------------------------------------------------------------- tasks
                        // Only authentication here, on purpose. Which tasks a person may see and act on is
                        // decided per task from their assignment and candidate groups, which a URL pattern
                        // cannot express; the system permission is asserted by the method annotations and the
                        // per-task rule by TaskAuthorizationService. A pattern rule here would look like the
                        // real control and be neither.
                        .requestMatchers("/api/tasks/**").authenticated()

                        // The assignee picker. Discloses usernames and display names to anybody signed in,
                        // which is what choosing an assignee requires, and no email or role.
                        .requestMatchers(HttpMethod.GET, "/api/users/available").authenticated()

                        // ------------------------------------------------------------------- groups
                        // Managing groups is managing who can reach which workflow, so it is ADMIN-only.
                        // The picker feed and the permission catalogue are open to any authenticated user,
                        // because sharing a workflow you own means choosing a group by name; the method
                        // annotations on the controller enforce the same split.
                        .requestMatchers(HttpMethod.GET, "/api/groups/available", "/api/groups/permissions")
                        .authenticated()
                        .requestMatchers("/api/groups/**").hasRole(Role.ADMIN.name())

                        // -------------------------------------------------------------------- nodes
                        // The node catalogue is needed by anyone who can open the designer, and it
                        // discloses only which node types exist.
                        .requestMatchers("/api/nodes/**").authenticated()

                        // ---------------------------------------------------------------- administration
                        // Belt and braces: the role here, the specific permission on each method.
                        .requestMatchers("/api/admin/**").hasRole(Role.ADMIN.name())

                        // Remaining actuator endpoints are administrative.
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())

                        // Anything not named above requires authentication. New endpoints are therefore
                        // protected by default rather than exposed by default.
                        .anyRequest().authenticated())

                // Before the username/password filter, which is where a bearer-token filter belongs so
                // that an authenticated request never reaches form-login handling.
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("Security is enabled: stateless JWT authentication, {} public endpoint patterns, "
                + "method security on, refresh token transport '{}'",
                PUBLIC_ENDPOINTS.length, properties.getJwt().getRefreshTokenTransport());
        return http.build();
    }

    /**
     * The password encoder.
     *
     * <p>Argon2id by default, with BCrypt retained for verification only. That is not indecision: it means a
     * database migrated from a BCrypt system keeps working, and {@code PasswordService} re-hashes each
     * legacy password to Argon2id on the next successful login. A single-algorithm encoder would force a
     * password reset on every existing user.
     *
     * <p>Argon2id is memory-hard, so a GPU or ASIC gains far less against it than against BCrypt, whose
     * working set fits trivially in silicon. Parameters come from configuration because the right cost
     * depends on the hardware and on the login rate the engine must absorb; the defaults are the OWASP
     * recommendation of 19 MiB, 2 iterations, 1 lane.
     *
     * <p>The {@code {id}} prefix format means the stored hash records which algorithm produced it, so this
     * decision can be revisited later without a migration.
     */
    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties properties) {
        AuthProperties.Password policy = properties.getPassword();

        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                policy.getSaltLength(),
                policy.getHashLength(),
                policy.getParallelism(),
                policy.getMemoryKb(),
                policy.getIterations());

        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("argon2", argon2);
        encoders.put("bcrypt", new BCryptPasswordEncoder(12));

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("argon2", encoders);
        // Handles hashes written before prefixes were used, so a legacy BCrypt value still verifies.
        delegating.setDefaultPasswordEncoderForMatches(
                PasswordEncoderFactories.createDelegatingPasswordEncoder());

        log.info("Password hashing: Argon2id (memory {} KiB, iterations {}, parallelism {}); "
                        + "BCrypt accepted for verification and upgraded on next login",
                policy.getMemoryKb(), policy.getIterations(), policy.getParallelism());
        return delegating;
    }
}
