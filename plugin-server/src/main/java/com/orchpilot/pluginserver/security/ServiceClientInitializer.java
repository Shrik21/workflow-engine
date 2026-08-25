package com.orchpilot.pluginserver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Registers the first service client from configuration, so a fresh installation can be wired up without a
 * chicken-and-egg problem.
 *
 * <p>Without it, registering a client needs an admin token, an admin token needs the workflow platform, and the
 * workflow platform needs a client. One bootstrap from environment variables breaks the loop.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It never logs the secret, never stores it in plaintext, and never overwrites an existing client. That last
 * point matters more than it looks: a redeployment with the environment variables still set would otherwise reset
 * the credential of a service that is running perfectly well, and the reset would look like a mysterious 401 storm
 * rather than a deployment mistake.
 *
 * <p>Configure with:
 *
 * <pre>
 * PLUGIN_SERVICE_CLIENT_ID=workflow-service
 * PLUGIN_SERVICE_CLIENT_SECRET=&lt;openssl rand -base64 32&gt;
 * </pre>
 */
@Component
@Order(20)
public class ServiceClientInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceClientInitializer.class);

    private final ServiceClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final com.orchpilot.pluginserver.config.PluginServerProperties properties;

    public ServiceClientInitializer(ServiceClientRepository clients, PasswordEncoder passwordEncoder,
                                    com.orchpilot.pluginserver.config.PluginServerProperties properties) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        /*
         * Read from configuration rather than straight from the environment.
         *
         * application.yml is where this platform keeps its configuration, and its values are written as
         * ${ENV:default}, so an environment variable still wins where one is set. Reading getenv directly, as
         * this did first, ignored application.yml entirely and made the documented way of configuring the
         * service the one way that did not work.
         */
        String clientId = properties.getBootstrapClient().getClientId();
        String secret = properties.getBootstrapClient().getClientSecret();

        if (isBlank(clientId) || isBlank(secret)) {
            long registered = clients.countByEnabledTrue();
            if (registered == 0) {
                log.warn("""
                        No service client is registered, so no workflow service can sync the catalogue.
                        Set plugin-server.bootstrap-client.client-id and .client-secret in application.yml, or \
                        the PLUGIN_SERVICE_CLIENT_ID and PLUGIN_SERVICE_CLIENT_SECRET environment variables, \
                        and restart. Generate a secret with: openssl rand -base64 32""");
            } else {
                log.info("{} service client(s) registered", registered);
            }
            return;
        }

        if (clients.existsById(clientId.trim())) {
            // Deliberately not an update. See the class comment: silently rotating a live credential on every
            // redeployment is worse than leaving it alone.
            log.info("Service client '{}' already exists; leaving its secret unchanged", clientId.trim());
            return;
        }

        if (secret.trim().length() < 24) {
            log.error("Refusing to register service client '{}': the secret is shorter than 24 characters. "
                    + "This credential can download every plugin in the registry.", clientId.trim());
            return;
        }

        ServiceClient client = new ServiceClient();
        client.setClientId(clientId.trim());
        client.setDescription("Bootstrapped from the environment at first start");
        client.setSecretHash(passwordEncoder.encode(secret));
        client.setAuthorities(PluginAuthority.serviceClientDefaults());
        client.setEnabled(true);
        client.setCreatedAt(Instant.now());
        client.setCreatedBy("bootstrap");
        clients.save(client);

        // The id and its authorities, never the secret. Whoever set the environment variable already has it.
        log.info("Registered service client '{}' with authorities {}", client.getClientId(),
                client.getAuthorities());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
