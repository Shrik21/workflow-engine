package com.orchpilot.workflow.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one-time administrator bootstrap, bound from {@code app.bootstrap-admin.*}.
 *
 * <p>No credential has a default. There is no {@code admin/admin}: an installation with a guessable
 * administrator is worse than one with none, because the second state is obvious and the first is not.
 * Every value comes from the environment, and the whole mechanism is off unless explicitly enabled.
 */
@ConfigurationProperties(prefix = "app.bootstrap-admin")
public class BootstrapAdminProperties {

    /** Off by default. Enable for a first start, then turn it off again. */
    private boolean enabled = false;

    private String username = "";
    private String email = "";

    /**
     * The initial password. Hashed with Argon2id at startup and never written anywhere in plaintext,
     * including the log.
     *
     * <p>Supply it through the environment rather than a committed file, and rotate it after first sign-in:
     * a value passed as an environment variable is visible to anything that can read the process
     * environment, which is a reasonable trade for a one-time bootstrap and not for a standing credential.
     */
    private String password = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** @return whether every required value is present */
    public boolean isComplete() {
        return enabled
                && username != null && !username.isBlank()
                && email != null && !email.isBlank()
                && password != null && !password.isBlank();
    }
}
