package com.orchpilot.pluginserver.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published API description.
 *
 * <p>Worth more here than on most services, because the primary consumer is another service rather than a
 * person. The workflow service's client is written against this document, and the contract between the two is
 * the thing most likely to drift as both change.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI pluginServerApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Plugin Server")
                        .version("1.0.0")
                        .description("""
                                Central registry, storage and distribution service for workflow plugin \
                                archives.

                                This service stores plugins and never runs them. Validation reads the declared \
                                manifest at META-INF/workflow-plugin.json and inspects the archive index; \
                                nothing here loads a class from an uploaded JAR. Plugins execute in the \
                                workflow service, inside an isolated class loader.

                                Two kinds of caller: an administrator with a user token who uploads and manages \
                                lifecycle, and a workflow service with a service token that may only read the \
                                catalogue and download archives.""")
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("A token issued by the workflow platform, or a service token for "
                                + "service-to-service calls.")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
