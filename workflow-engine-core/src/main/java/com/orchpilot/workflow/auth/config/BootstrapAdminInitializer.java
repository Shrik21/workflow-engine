package com.orchpilot.workflow.auth.config;

import com.orchpilot.workflow.audit.SecurityAuditEvent;
import com.orchpilot.workflow.audit.SecurityAuditService;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.repository.UserRepository;
import com.orchpilot.workflow.auth.service.PasswordPolicyException;
import com.orchpilot.workflow.auth.service.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates the first administrator, once, from environment configuration.
 *
 * <pre>
 * enabled?  ── no ──► do nothing
 *    │
 *   yes
 *    │
 * an ADMIN already exists?  ── yes ──► do nothing
 *    │
 *    no
 *    │
 *    └──► hash the password, create the account, audit it
 * </pre>
 *
 * <p>Idempotent by checking for the <em>role</em> rather than for the configured username. Checking the
 * username would recreate a deleted or renamed administrator on the next restart, quietly resurrecting an
 * account somebody deliberately removed.
 *
 * <p>The plaintext password is hashed immediately and never logged. The log records that an administrator
 * was created and with which username, which is what an operator needs to know, and nothing that would let
 * a reader of the logs sign in.
 */
@Component
@Order(10)
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final BootstrapAdminProperties properties;
    private final UserRepository users;
    private final PasswordService passwords;
    private final SecurityAuditService audit;

    public BootstrapAdminInitializer(BootstrapAdminProperties properties, UserRepository users,
                                     PasswordService passwords, SecurityAuditService audit) {
        this.properties = properties;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            warnIfNoAdmin();
            return;
        }
        if (!properties.isComplete()) {
            log.error("app.bootstrap-admin.enabled is true but username, email or password is missing. "
                    + "No administrator was created. Set BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_EMAIL "
                    + "and BOOTSTRAP_ADMIN_PASSWORD.");
            return;
        }

        if (users.existsByRolesContaining(Role.ADMIN)) {
            log.info("Bootstrap is enabled but an administrator already exists; nothing to do. "
                    + "Disable app.bootstrap-admin.enabled to remove this check from startup.");
            return;
        }

        String username = properties.getUsername().trim().toLowerCase(Locale.ROOT);
        String email = properties.getEmail().trim().toLowerCase(Locale.ROOT);

        if (users.existsByUsername(username) || users.existsByEmail(email)) {
            // An account with this identity exists but is not an ADMIN. Promoting it silently would be a
            // privilege escalation triggered by configuration, so it is refused and reported instead.
            log.error("Cannot bootstrap administrator '{}': an account with that username or email already "
                    + "exists without the ADMIN role. Grant the role through the API instead.", username);
            return;
        }

        try {
            User admin = new User();
            admin.setUsername(username);
            admin.setEmail(email);
            // Validated against the policy, so a weak bootstrap password is refused rather than accepted
            // for the single most privileged account on the platform.
            admin.setPasswordHash(passwords.hash(properties.getPassword()));
            admin.setFirstName("Platform");
            admin.setLastName("Administrator");
            admin.setRoles(Set.of(Role.ADMIN));
            admin.setEnabled(true);
            admin.setCreatedAt(Instant.now());
            admin.setUpdatedAt(Instant.now());
            admin.setCreatedBy("bootstrap");

            User saved = users.save(admin);
            audit.success(SecurityAuditEvent.USER_CREATED, saved.getId(), saved.getUsername(), null,
                    Map.of("roles", Set.of(Role.ADMIN.name()), "source", "bootstrap"));

            log.warn("Created the bootstrap administrator '{}'. Sign in, change the password, then set "
                    + "app.bootstrap-admin.enabled=false and clear BOOTSTRAP_ADMIN_PASSWORD from the "
                    + "environment.", saved.getUsername());
        } catch (PasswordPolicyException ex) {
            log.error("The bootstrap administrator password does not meet the policy, so no administrator "
                    + "was created: {}", ex.getViolations());
        } catch (RuntimeException ex) {
            log.error("Failed to create the bootstrap administrator: {}", ex.getMessage());
        }
    }

    /**
     * Notes the absence of any administrator.
     *
     * <p>Worth a warning rather than silence: the platform still runs and still serves workflows, but nobody
     * can install a plugin, manage a secret or create a user, and the reason would otherwise only surface as
     * an unexplained 403.
     */
    private void warnIfNoAdmin() {
        try {
            if (!users.existsByRolesContaining(Role.ADMIN)) {
                log.warn("No administrator account exists. Plugin, secret and user management will answer "
                        + "403 for everyone. Set app.bootstrap-admin.* and restart to create one.");
            }
        } catch (RuntimeException ex) {
            log.debug("Could not check for an existing administrator: {}", ex.getMessage());
        }
    }
}
