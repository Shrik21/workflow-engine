package com.orchpilot.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Cross-origin access for the console.
 *
 * <p>Needed because the Angular dev server and the engine are different origins: a browser on
 * {@code http://localhost:4200} calling {@code http://localhost:8080} is a cross-origin request, and
 * without a matching CORS response the browser rejects it before the engine ever sees it. That failure is
 * indistinguishable from the engine being unreachable, which is why it is configured explicitly.
 *
 * <p>Exposed as a {@link CorsConfigurationSource} rather than as a standalone filter so that Spring
 * Security applies it inside its own filter chain. That ordering matters: a CORS preflight is an
 * {@code OPTIONS} request carrying no credentials, so it has to be answered before authentication is
 * considered, and letting the security chain own it is how that is guaranteed rather than arranged by
 * filter ordering.
 *
 * <p>Two deliberate restrictions:
 *
 * <ul>
 *   <li><b>Named origins only, never a wildcard.</b> These endpoints install executable code and read
 *       credentials. {@code Access-Control-Allow-Origin: *} would let any page a browser happens to load
 *       drive them.</li>
 *   <li><b>Credentials are not allowed.</b> The console authenticates with a bearer token in a header, not
 *       with cookies, so browsers must not attach ambient credentials to cross-origin API calls. The one
 *       cookie the platform issues is the refresh token, which is {@code SameSite=Strict} and therefore
 *       deliberately not sent cross-site at all.</li>
 * </ul>
 *
 * <p>In production, prefer serving the console and the API from one origin through a reverse proxy, as the
 * shipped nginx configuration does. No origin then needs listing here.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** Request headers the console sends that are not on the CORS safelist. */
    private static final List<String> ALLOWED_HEADERS = List.of(
            "Authorization", "Content-Type", "Accept", "X-Actor", "X-Requested-With");

    /** Response headers a browser is permitted to read. */
    private static final List<String> EXPOSED_HEADERS = List.of("Location", "Content-Disposition");

    @Bean
    public CorsConfigurationSource corsConfigurationSource(WorkflowEngineProperties properties) {
        List<String> origins = properties.getSecurity().getAllowedOrigins();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);
        configuration.setMaxAge(1800L);

        /*
         * True only when the refresh token travels as a cookie AND explicit origins are configured.
         * A cross-origin refresh needs the browser to send the cookie, which needs credentials mode; but
         * allowCredentials is incompatible with a wildcard origin, and combining it with a permissive
         * origin list is how CORS misconfigurations become account takeovers. Named origins only.
         */
        boolean allowCredentials = properties.getSecurity().isAllowCredentials() && !origins.isEmpty();
        configuration.setAllowCredentials(allowCredentials);

        if (origins.isEmpty()) {
            log.info("CORS is disabled: workflow.engine.security.allowed-origins is empty. Serve the "
                    + "console from the same origin as the API, for example through a reverse proxy.");
        } else {
            for (String origin : origins) {
                String trimmed = origin == null ? "" : origin.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if ("*".equals(trimmed)) {
                    // Refused rather than honoured. An API that installs plugins and reads credentials
                    // must not be callable from any origin, and silently accepting a wildcard here would
                    // be the easiest possible misconfiguration to make.
                    log.error("Ignoring '*' in workflow.engine.security.allowed-origins: a wildcard origin "
                            + "is not permitted for this API. List exact origins instead.");
                    continue;
                }
                configuration.addAllowedOrigin(trimmed);
            }
            log.info("CORS enabled for origin(s) {} (credentials {})",
                    configuration.getAllowedOrigins(), allowCredentials ? "allowed" : "denied");
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/**", configuration);
        return source;
    }
}
