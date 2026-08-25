package com.orchpilot.pluginserver.user;

import com.orchpilot.pluginserver.role.Role;
import com.orchpilot.pluginserver.security.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Creates the first administrator, once, on an empty registry.
 *
 * <h2>Only when there are no accounts at all</h2>
 *
 * Not "when the admin account is missing". An installation that deliberately renamed or removed its
 * administrator should not have one silently recreated on the next restart, which would be a way to get an
 * account back that somebody meant to be gone.
 *
 * <h2>No default password, ever</h2>
 *
 * A password shipped in a configuration file is a published password: every copy of this service has it, and
 * so does anybody who has read the repository. Without one configured, a strong password is generated, printed
 * once to the log, and marked as requiring replacement at first sign-in. The operator reads it from the log
 * they are already watching during a first start, uses it once, and it stops working.
 *
 * <p>Runs after {@code RoleService} has seeded the roles, because the account it creates needs one to exist.
 */
@Component
@Order(100)
public class InitialAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminInitializer.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserService users;
    private final AuthProperties properties;

    public InitialAdminInitializer(UserService users, AuthProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        AuthProperties.InitialAdmin config = properties.getInitialAdmin();
        if (!config.isEnabled()) {
            return;
        }
        if (!users.isEmpty()) {
            return;
        }

        boolean generated = config.getPassword().isBlank();
        String password = generated ? generatePassword() : config.getPassword();

        users.create(new UserService.NewUser(
                config.getUsername(),
                config.getEmail(),
                "Registry",
                "Administrator",
                password,
                Set.of(Role.PLUGIN_ADMIN),
                false,
                // Always. A configured password has been seen by whatever holds the configuration; a
                // generated one has been printed to a log. Neither should remain the account's password.
                true), "bootstrap");

        if (generated) {
            log.warn("""

                    ================= FIRST ADMINISTRATOR CREATED =================
                    No accounts existed, so one was created:

                      username: {}
                      password: {}

                    This password was generated for this start only, is written in this log, and must be
                    changed at first sign-in. Set plugin-server.auth.initial-admin.password, or better
                    INITIAL_ADMIN_PASSWORD, to choose your own.
                    ==============================================================
                    """, config.getUsername(), password);
        } else {
            log.info("Created the first administrator '{}' from configuration. It must change its password "
                    + "at first sign-in.", config.getUsername());
        }
    }

    /**
     * A password strong enough that the log line is the only way to learn it.
     *
     * <p>Base64 of 24 random bytes, then decorated so it satisfies the policy's character-class rules — which
     * a Base64 string does not always do on its own, and a bootstrap that fails its own policy check would be
     * an unhelpful way to discover that.
     */
    private static String generatePassword() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
