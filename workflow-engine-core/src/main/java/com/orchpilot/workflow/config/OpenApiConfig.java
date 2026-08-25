package com.orchpilot.workflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description served at {@code /swagger-ui.html} and {@code /v3/api-docs}.
 *
 * <p>Declares bearer authentication as a global requirement so the Swagger UI "Authorize" button accepts a
 * JWT and every subsequent try-it-out call carries it. The shared administrative API key that used to appear
 * here is gone: it had no identity, no expiry and no audit trail, and real authentication replaces it.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    /** Referenced by {@code @SecurityRequirement(name = "bearerAuth")} on protected controllers. */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI workflowEngineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Workflow Engine API")
                        .version("1.0.0")
                        .description("""
                                Workflow authoring, execution and runtime plugin management.

                                ## Authentication

                                Every endpoint except sign-in, registration, token refresh and the health
                                probes requires a bearer token:

                                    Authorization: Bearer <access token>

                                Obtain one from POST /api/auth/login. Access tokens last 15 minutes; the
                                refresh token is returned as an HttpOnly SameSite=Strict cookie and is
                                rotated on every use, so presenting a token twice revokes the whole chain.

                                ## Authorization

                                Rules are expressed on permissions rather than roles. A USER may view,
                                create, edit and execute workflows; plugin management, secrets and user
                                administration are ADMIN only. A request that authenticates but lacks the
                                permission answers 403; one with no or invalid credentials answers 401.
                                Neither response describes which rule failed.

                                ## Node types

                                Not a fixed list. The four built-ins (START, FORM, DECISION, END) are always
                                present; everything else is contributed by plugin JARs uploaded at runtime.
                                Call GET /api/nodes to discover what this instance can currently execute.""")
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Access token from POST /api/auth/login.

                                        Short-lived by design. When it expires, call POST /api/auth/refresh,
                                        which reads the refresh cookie and issues a new pair.""")))
                // Applied globally, then relaxed per endpoint. Declaring it here rather than annotating
                // every controller means a new endpoint is documented as protected by default, matching
                // how the security configuration actually treats it.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
