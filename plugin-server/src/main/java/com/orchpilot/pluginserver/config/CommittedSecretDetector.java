package com.orchpilot.pluginserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Notices when the registry is running on the secrets committed to {@code application.yml}.
 *
 * <p>The same discipline the workflow engine applies to its own keys, for the same reason and against a higher
 * stake. The engine's committed key lets somebody forge a token; this one lets somebody obtain a service token
 * for a registry that distributes executable code to every workflow service that syncs from it.
 *
 * <p>So: a warning naming exactly which secrets are the published ones at every start, and a refusal to start
 * under a production-like profile. A convenience that quietly reaches production is worse than no convenience.
 */
@Component
@Order(5)
public class CommittedSecretDetector implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommittedSecretDetector.class);

    /**
     * The values shipped in {@code application.yml}.
     *
     * <p>Compared as literals rather than against the current defaults, so copying one into an environment
     * variable or a Kubernetes secret does not launder it: the string is published either way.
     */
    private static final Set<String> PUBLISHED = Set.of(
            "e/tS0wG7srmEpDabtR1DT5Vb+DYE8ztoz+THCMvsyYdReAWguOkUvFyMWz+w5V4/",
            "r7lITM+Kv30KJNrcTKOJdzh1b+rRpDhMN1oLn817YFk=");

    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "staging");

    private final PluginServerProperties properties;
    private final Environment environment;

    public CommittedSecretDetector(PluginServerProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> published = new ArrayList<>();

        if (PUBLISHED.contains(trim(properties.getSecurity().getJwtSecret()))) {
            published.add("plugin-server.security.jwt-secret (PLUGIN_SERVER_JWT_SECRET) - anyone with this "
                    + "can mint a token this registry accepts, including one that can upload a plugin");
        }
        if (PUBLISHED.contains(trim(properties.getBootstrapClient().getClientSecret()))) {
            published.add("plugin-server.bootstrap-client.client-secret (PLUGIN_SERVICE_CLIENT_SECRET) - "
                    + "anyone with this can download every plugin archive in the registry");
        }

        if (published.isEmpty()) {
            log.info("No committed development secrets are in use; every value came from configuration you "
                    + "supplied.");
            return;
        }

        boolean productionLike = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> PRODUCTION_PROFILES.contains(profile.toLowerCase(Locale.ROOT)));

        if (productionLike) {
            throw new IllegalStateException(String.join(System.lineSeparator(),
                    "Refusing to start with the development secrets committed to application.yml while the '"
                            + String.join(",", environment.getActiveProfiles()) + "' profile is active.",
                    "",
                    "These values are published in version control:",
                    bullets(published),
                    "",
                    "Supply real values, which take precedence over application.yml:",
                    "",
                    "    PLUGIN_SERVER_JWT_SECRET        openssl rand -base64 48",
                    "    PLUGIN_SERVICE_CLIENT_SECRET    openssl rand -base64 32",
                    "",
                    "The JWT secret must match the workflow engine's security.jwt.secret, and the client",
                    "secret must match its plugin.server.client-secret, or the two services cannot talk."));
        }

        log.warn(System.lineSeparator() + String.join(System.lineSeparator(),
                "===============================================================================",
                " The plugin registry is running with development secrets committed to application.yml.",
                "",
                bullets(published),
                "",
                " These are strong random values and they are public: they are in version control and",
                " identical in every checkout. Fine for local work, unusable for anything shared.",
                "",
                " Override them with environment variables, or edit application.yml. Startup will fail",
                " outright if these values are still in place when the prod profile is active.",
                "==============================================================================="));
    }

    private static String bullets(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append("   * ").append(line).append(System.lineSeparator());
        }
        return text.toString().stripTrailing();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
