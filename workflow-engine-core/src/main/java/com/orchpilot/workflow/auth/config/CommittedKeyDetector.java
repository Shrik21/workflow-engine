package com.orchpilot.workflow.auth.config;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Notices when the engine is running on the keys committed to {@code application.yml}.
 *
 * <p>Those keys exist so the application starts with no environment setup, which is a real convenience and
 * a real risk. The risk is not that they are weak; they are 384 and 256 bits of randomness. The risk is that
 * they are <em>published</em>: anyone who can read the repository can mint a valid administrator token and
 * decrypt every stored secret, and every checkout shares the same values.
 *
 * <p>A committed key is only dangerous when nobody notices it, so this class makes it impossible not to:
 *
 * <ul>
 *   <li>Every start logs a warning naming exactly which keys are the published ones.</li>
 *   <li>With the {@code prod} profile active, startup <strong>fails</strong>. A convenience that quietly
 *       reaches production is worse than no convenience, and a profile named {@code prod} is the clearest
 *       available signal that this is not a developer's laptop.</li>
 * </ul>
 *
 * <p>Deliberately not tied to the {@code dev} profile. Guarding on the presence of {@code dev} would let an
 * unprofiled deployment, which is the most common accident, pass silently.
 */
@Component
@Order(5)
public class CommittedKeyDetector implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommittedKeyDetector.class);

    /**
     * The values shipped in {@code application.yml}.
     *
     * <p>Duplicated here rather than read from configuration on purpose: the point is to recognise these
     * specific published strings even if someone copies them into an environment variable, a Kubernetes
     * secret or a different property file, all of which would defeat a check that simply compared against
     * the current default.
     */
    private static final Set<String> PUBLISHED_KEYS = Set.of(
            "UAWPiV9j6JIG+UpiPcoiObQLUyt/c10V2Gokz9ahQvv4u3kY7hJke40GDAVFiWOV",
            "IUuAXUw8gjaGtQGTTyKie34CSJZxMbySRTzr6iS9C5g=",
            "qrMX2uikfv0bPI/EdZQZeMZHwe/yyMRvlET5vovBSuc=",
            // The plugin registry client secret shipped in application.yml. A credential for downloading
            // executable code, so it belongs on this list even though it grants no access to data.
            "r7lITM+Kv30KJNrcTKOJdzh1b+rRpDhMN1oLn817YFk=");

    /** The bootstrap password shipped in {@code application.yml}. */
    private static final String PUBLISHED_ADMIN_PASSWORD = "OrchPilot-Dev-Adm1n!";

    /** Profiles that mean "this is not a laptop". */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "staging");

    private final AuthProperties auth;
    private final WorkflowEngineProperties engine;
    private final BootstrapAdminProperties bootstrap;
    private final com.orchpilot.workflow.pluginserver.PluginServerProperties pluginServer;
    private final Environment environment;

    public CommittedKeyDetector(AuthProperties auth, WorkflowEngineProperties engine,
                                BootstrapAdminProperties bootstrap,
                                com.orchpilot.workflow.pluginserver.PluginServerProperties pluginServer,
                                Environment environment) {
        this.auth = auth;
        this.engine = engine;
        this.bootstrap = bootstrap;
        this.pluginServer = pluginServer;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> published = new ArrayList<>();

        if (isPublished(auth.getJwt().getSecret())) {
            published.add("security.jwt.secret (JWT_SECRET) - anyone with this can forge a token for any "
                    + "user, including an administrator");
        }
        if (isPublished(auth.getEncryption().getKey())) {
            published.add("security.encryption.key (APP_ENCRYPTION_KEY) - anyone with this can decrypt "
                    + "every stored plugin credential");
        }
        if (isPublished(engine.getSecrets().getMasterKey())) {
            published.add("workflow.engine.secrets.master-key (WORKFLOW_SECRETS_KEY) - anyone with this "
                    + "can decrypt every stored workflow secret");
        }
        if (isPublished(pluginServer.getClientSecret())) {
            published.add("plugin.server.client-secret (PLUGIN_SERVER_CLIENT_SECRET) - anyone with this can "
                    + "download every plugin archive from the registry, which is executable code");
        }
        if (PUBLISHED_ADMIN_PASSWORD.equals(bootstrap.getPassword())) {
            published.add("app.bootstrap-admin.password (BOOTSTRAP_ADMIN_PASSWORD) - the administrator "
                    + "account can be signed into by anyone who has read this repository");
        }

        if (published.isEmpty()) {
            log.info("No committed development keys are in use; every key came from configuration you supplied.");
            return;
        }

        String profiles = String.join(",", environment.getActiveProfiles());
        boolean productionLike = environment.getActiveProfiles().length > 0
                && java.util.Arrays.stream(environment.getActiveProfiles())
                        .anyMatch(profile -> PRODUCTION_PROFILES.contains(profile.toLowerCase(java.util.Locale.ROOT)));

        if (productionLike) {
            // Refusing to start is the whole point of this class. A published key in a production
            // deployment is not a warning-level problem.
            throw new IllegalStateException(String.join(System.lineSeparator(),
                    "Refusing to start with the development keys committed to application.yml while the '"
                            + profiles + "' profile is active.",
                    "",
                    "These keys are published in version control:",
                    bullets(published),
                    "",
                    "Supply real values as environment variables, which take precedence over application.yml:",
                    "",
                    "    JWT_SECRET             openssl rand -base64 48",
                    "    APP_ENCRYPTION_KEY     openssl rand -base64 32",
                    "    WORKFLOW_SECRETS_KEY   openssl rand -base64 32",
                    "    BOOTSTRAP_ADMIN_PASSWORD",
                    "",
                    "Note that changing an encryption key makes anything already encrypted with the old one",
                    "unreadable, so rotate before storing secrets rather than after."));
        }

        log.warn(System.lineSeparator() + String.join(System.lineSeparator(),
                "===============================================================================",
                " Running with development keys that are committed to application.yml.",
                "",
                bullets(published),
                "",
                " These are strong random values, but they are public: they are in version control and",
                " identical in every checkout. Fine for local work, unusable for anything shared.",
                "",
                " Override them with environment variables, or edit application.yml. Startup will fail",
                " outright if these values are still in place when the prod profile is active.",
                "==============================================================================="));
    }

    private static boolean isPublished(String value) {
        return value != null && PUBLISHED_KEYS.contains(value.trim());
    }

    private static String bullets(List<String> items) {
        return items.stream().map(item -> "   * " + item)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }
}
