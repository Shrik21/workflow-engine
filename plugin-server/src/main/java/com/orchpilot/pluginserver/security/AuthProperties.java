package com.orchpilot.pluginserver.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * How this registry authenticates people.
 *
 * <p>Separate from {@code PluginServerProperties}, which describes how the registry stores plugins. Mixing the
 * two would mean somebody tuning an upload limit is editing the same block as the token lifetime.
 *
 * <p>Nothing here has a secret as a default. A signing key absent from configuration produces an ephemeral one
 * with a loud warning in development and a refusal to start in production, which is the only pair of behaviours
 * that is both convenient and safe.
 */
@ConfigurationProperties(prefix = "plugin-server.auth")
public class AuthProperties {

    private final Jwt jwt = new Jwt();
    private final Cookie cookie = new Cookie();
    private final Password password = new Password();
    private final Login login = new Login();
    private final Registration registration = new Registration();
    private final InitialAdmin initialAdmin = new InitialAdmin();

    /**
     * Browser origins allowed to call this API.
     *
     * <p>Named explicitly, never a wildcard. A credentialed request from any origin is a request every site
     * the operator's browser visits can make on their behalf.
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:4300"));

    public Jwt getJwt() {
        return jwt;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public Password getPassword() {
        return password;
    }

    public Login getLogin() {
        return login;
    }

    public Registration getRegistration() {
        return registration;
    }

    public InitialAdmin getInitialAdmin() {
        return initialAdmin;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    /** Token issuance and lifetimes. */
    public static class Jwt {

        /**
         * Short, because permissions are carried in the token.
         *
         * <p>A token states what its holder may do, so nothing is re-read from the database on each request —
         * which is fast, and means a permission removed from a role stays effective until the token expires.
         * Fifteen minutes is the bound on how stale that can be, and is the reason not to raise it much.
         */
        private Duration accessTokenTtl = Duration.ofMinutes(15);

        private Duration refreshTokenTtl = Duration.ofDays(7);

        private String issuer = "plugin-registry";

        /**
         * PEM private key for RS256. Preferred: this service then signs, and holders only verify.
         *
         * <p>Supplied from the environment or a mounted secret. Never committed.
         */
        private String privateKey = "";

        /** PEM public key matching {@link #privateKey}. */
        private String publicKey = "";

        /**
         * HMAC secret, for development only.
         *
         * <p>Symmetric signing means anything that can verify can also forge, which is acceptable when the
         * only holder is this service and unacceptable the moment a key is shared.
         */
        private String hmacSecret = "";

        /**
         * Whether a missing key may be generated at startup.
         *
         * <p>True is right for a laptop: the registry starts with no setup and every token dies with the
         * process. It is refused under a production profile, where a key that changes on restart would sign
         * everybody out on every deploy and could not be verified by anything else.
         */
        private boolean allowEphemeralKey = true;

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey == null ? "" : privateKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey == null ? "" : publicKey;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret == null ? "" : hmacSecret;
        }

        public boolean isAllowEphemeralKey() {
            return allowEphemeralKey;
        }

        public void setAllowEphemeralKey(boolean allowEphemeralKey) {
            this.allowEphemeralKey = allowEphemeralKey;
        }

        /** @return whether an RSA key pair was supplied */
        public boolean hasRsaKeys() {
            return !privateKey.isBlank() && !publicKey.isBlank();
        }
    }

    /**
     * The refresh cookie.
     *
     * <p>The name is the thing that keeps this registry's session separate from the workflow platform's.
     * Cookies are scoped by host and path and <b>not by port</b>, so two consoles on different ports of the
     * same host share one jar; only distinct names keep their sessions from overwriting each other. Changing
     * this to match another service's cookie name would make signing in to either one sign the operator out
     * of the other.
     */
    public static class Cookie {

        /**
         * Whether the refresh token travels in a cookie rather than the response body.
         *
         * <p>A cookie is preferred: {@code HttpOnly} makes the token unreadable by script, and it survives a
         * page reload, which is what stops a refresh from ending the session. Turn it off for a non-browser
         * client that would rather hold the token itself.
         */
        private boolean enabled = true;

        /** Distinct from every other service's. See the class note. */
        private String name = "plugin_registry_refresh";

        /** Scoped to the endpoints that consume it, so it is not attached to every request. */
        private String path = "/api/auth";

        /**
         * {@code Strict}: the browser sends this only on this application's own requests. With bearer
         * authentication everywhere else, there is nothing left for a cross-site request to achieve.
         */
        private String sameSite = "Strict";

        /**
         * Set true wherever TLS terminates in front of this service.
         *
         * <p>False by default only because a development console is served over plain HTTP, and a
         * {@code Secure} cookie there is silently dropped — which looks exactly like a broken session.
         */
        private boolean secure = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }
    }

    /** Hashing parameters and the policy new passwords must satisfy. */
    public static class Password {

        private int minLength = 12;
        private int maxLength = 128;
        private boolean requireUppercase = true;
        private boolean requireLowercase = true;
        private boolean requireDigit = true;
        private boolean requireSpecial = true;

        // Argon2id parameters. The defaults follow OWASP's guidance for a server-side login path.
        private int saltLength = 16;
        private int hashLength = 32;
        private int parallelism = 1;
        private int memoryKb = 19456;
        private int iterations = 2;

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        public boolean isRequireUppercase() {
            return requireUppercase;
        }

        public void setRequireUppercase(boolean requireUppercase) {
            this.requireUppercase = requireUppercase;
        }

        public boolean isRequireLowercase() {
            return requireLowercase;
        }

        public void setRequireLowercase(boolean requireLowercase) {
            this.requireLowercase = requireLowercase;
        }

        public boolean isRequireDigit() {
            return requireDigit;
        }

        public void setRequireDigit(boolean requireDigit) {
            this.requireDigit = requireDigit;
        }

        public boolean isRequireSpecial() {
            return requireSpecial;
        }

        public void setRequireSpecial(boolean requireSpecial) {
            this.requireSpecial = requireSpecial;
        }

        public int getSaltLength() {
            return saltLength;
        }

        public void setSaltLength(int saltLength) {
            this.saltLength = saltLength;
        }

        public int getHashLength() {
            return hashLength;
        }

        public void setHashLength(int hashLength) {
            this.hashLength = hashLength;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getMemoryKb() {
            return memoryKb;
        }

        public void setMemoryKb(int memoryKb) {
            this.memoryKb = memoryKb;
        }

        public int getIterations() {
            return iterations;
        }

        public void setIterations(int iterations) {
            this.iterations = iterations;
        }
    }

    /** Failed sign-in handling. */
    public static class Login {

        private int maxFailedAttempts = 5;

        /**
         * How long a lock lasts.
         *
         * <p>Timed rather than permanent. A permanent lock turns somebody mistyping their password into a
         * support ticket, and hands an attacker a way to disable any account they can name.
         */
        private Duration lockDuration = Duration.ofMinutes(15);

        /** The window failures are counted over, so old mistakes do not accumulate into a lock. */
        private Duration failureWindow = Duration.ofMinutes(15);

        public int getMaxFailedAttempts() {
            return maxFailedAttempts;
        }

        public void setMaxFailedAttempts(int maxFailedAttempts) {
            this.maxFailedAttempts = maxFailedAttempts;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }

        public Duration getFailureWindow() {
            return failureWindow;
        }

        public void setFailureWindow(Duration failureWindow) {
            this.failureWindow = failureWindow;
        }
    }

    /** Self-registration. Off by default: a registry distributes executable code. */
    public static class Registration {

        private boolean enabled = false;

        /** What a self-registered account gets. Read-only, and never configurable to an administrative role. */
        private String defaultRole = "PLUGIN_VIEWER";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultRole() {
            return defaultRole;
        }

        public void setDefaultRole(String defaultRole) {
            this.defaultRole = defaultRole;
        }
    }

    /** The first administrator, created only when the collection holds no accounts at all. */
    public static class InitialAdmin {

        private boolean enabled = true;
        private String username = "admin";
        private String email = "admin@plugin-registry.local";

        /**
         * The initial password. Never defaulted.
         *
         * <p>Absent, the registry generates one, prints it once to the log and requires it to be changed at
         * first sign-in. A shipped default password is a published password.
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
            this.password = password == null ? "" : password;
        }
    }
}
