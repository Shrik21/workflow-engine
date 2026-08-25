package com.orchpilot.workflow.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication configuration, bound from {@code security.*}.
 *
 * <p>No secret has a default. The JWT signing key and the encryption key must come from the
 * environment; when they are absent the application fails to start rather than falling back to a
 * built-in value, because a shipped default signing key is indistinguishable from no authentication at
 * all.
 *
 * <p>Durations are plain millisecond longs rather than {@code Duration}, so the configuration reads
 * exactly as the operator wrote it and there is no ambiguity about the default unit.
 */
@ConfigurationProperties(prefix = "security")
public class AuthProperties {

    private final Jwt jwt = new Jwt();
    private final Password password = new Password();
    private final Lockout lockout = new Lockout();
    private final Registration registration = new Registration();
    private final Encryption encryption = new Encryption();

    public Jwt getJwt() {
        return jwt;
    }

    public Password getPassword() {
        return password;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Registration getRegistration() {
        return registration;
    }

    public Encryption getEncryption() {
        return encryption;
    }

    /** How access tokens are signed and how long credentials live. */
    public static class Jwt {

        /** Signing algorithm: {@code HS256} or {@code RS256}. */
        private String algorithm = "HS256";

        /**
         * HMAC secret for HS256. Must be at least 32 bytes; the application refuses to start otherwise,
         * because a short key undermines the signature regardless of the algorithm's strength.
         */
        private String secret = "";

        /** PEM-encoded PKCS#8 private key for RS256. Required when the algorithm is RS256. */
        private String privateKey = "";

        /** PEM-encoded X.509 public key for RS256. Required when the algorithm is RS256. */
        private String publicKey = "";

        /** Key id published in the JWK set and in the token header, so keys can be rotated. */
        private String keyId = "workflow-key-1";

        /** Issuer claim, validated on every incoming token. */
        private String issuer = "workflow-platform";

        /** Access token lifetime. Short by design: authority changes take effect within this window. */
        private long accessTokenExpiration = 900_000;

        /** Refresh token lifetime. */
        private long refreshTokenExpiration = 604_800_000L;

        /**
         * Where the refresh token is carried: {@code cookie} or {@code body}.
         *
         * <p>{@code cookie} is the default and the safer option: an HttpOnly cookie is unreadable by
         * injected script. {@code body} exists for non-browser clients and for the case where the console
         * is served from a different origin than the API, where a SameSite=Strict cookie would not be
         * sent. Choosing {@code body} means the token becomes reachable by JavaScript, which is a real
         * downgrade and should be a considered decision.
         */
        private String refreshTokenTransport = "cookie";

        private String cookieName = "workflow_refresh_token";

        /**
         * Cookie path. Scoped to the refresh endpoint so the token is not attached to ordinary API calls,
         * which limits both accidental logging and the reach of any CSRF attempt.
         */
        private String cookiePath = "/api/auth";

        /** {@code Strict}, {@code Lax} or {@code None}. {@code None} additionally requires Secure. */
        private String cookieSameSite = "Strict";

        /**
         * Force the Secure attribute. When false, it is still set automatically for requests that arrive
         * over HTTPS, so a plain-HTTP local setup works without making production insecure.
         */
        private boolean cookieSecure = false;

        /** Maximum live refresh tokens per user, which bounds the number of concurrent sessions. */
        private int maxSessionsPerUser = 10;

        /**
         * Generate a random signing key at startup when none is configured, instead of refusing to start.
         *
         * <p>Set by the {@code dev} profile and false everywhere else. Convenient locally and wrong anywhere
         * else: tokens stop working across restarts, and two instances cannot verify each other's. Random
         * rather than a fixed development constant, because a hardcoded secret in the repository would end
         * up in production images.
         */
        private boolean allowEphemeralSecret = false;

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public long getAccessTokenExpiration() {
            return accessTokenExpiration;
        }

        public void setAccessTokenExpiration(long accessTokenExpiration) {
            this.accessTokenExpiration = accessTokenExpiration;
        }

        public long getRefreshTokenExpiration() {
            return refreshTokenExpiration;
        }

        public void setRefreshTokenExpiration(long refreshTokenExpiration) {
            this.refreshTokenExpiration = refreshTokenExpiration;
        }

        public String getRefreshTokenTransport() {
            return refreshTokenTransport;
        }

        public void setRefreshTokenTransport(String refreshTokenTransport) {
            this.refreshTokenTransport = refreshTokenTransport;
        }

        public String getCookieName() {
            return cookieName;
        }

        public void setCookieName(String cookieName) {
            this.cookieName = cookieName;
        }

        public String getCookiePath() {
            return cookiePath;
        }

        public void setCookiePath(String cookiePath) {
            this.cookiePath = cookiePath;
        }

        public String getCookieSameSite() {
            return cookieSameSite;
        }

        public void setCookieSameSite(String cookieSameSite) {
            this.cookieSameSite = cookieSameSite;
        }

        public boolean isCookieSecure() {
            return cookieSecure;
        }

        public void setCookieSecure(boolean cookieSecure) {
            this.cookieSecure = cookieSecure;
        }

        public int getMaxSessionsPerUser() {
            return maxSessionsPerUser;
        }

        public void setMaxSessionsPerUser(int maxSessionsPerUser) {
            this.maxSessionsPerUser = maxSessionsPerUser;
        }

        public boolean isAllowEphemeralSecret() {
            return allowEphemeralSecret;
        }

        public void setAllowEphemeralSecret(boolean allowEphemeralSecret) {
            this.allowEphemeralSecret = allowEphemeralSecret;
        }

        /** @return whether the refresh token travels in a cookie rather than the response body */
        public boolean isCookieTransport() {
            return !"body".equalsIgnoreCase(refreshTokenTransport);
        }

        /** @return whether tokens are signed with an asymmetric key */
        public boolean isAsymmetric() {
            return "RS256".equalsIgnoreCase(algorithm);
        }
    }

    /**
     * Password policy and Argon2id cost.
     *
     * <p>Enforced on the server. The Angular form applies the same rules for immediate feedback, but the
     * server does not trust it: every registration, admin creation and password change re-validates.
     */
    public static class Password {

        private int minLength = 12;
        private int maxLength = 128;
        private boolean requireUppercase = true;
        private boolean requireLowercase = true;
        private boolean requireDigit = true;
        private boolean requireSpecial = true;

        /** Argon2id salt length in bytes. */
        private int saltLength = 16;

        /** Argon2id output length in bytes. */
        private int hashLength = 32;

        /** Argon2id lanes. */
        private int parallelism = 1;

        /** Argon2id memory cost in kibibytes. The OWASP recommendation is 19456, which is 19 MiB. */
        private int memoryKb = 19_456;

        /** Argon2id passes over memory. */
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

    /** Brute-force throttle. */
    public static class Lockout {

        private boolean enabled = true;

        /** Failures within the window before the identifier is locked out. */
        private int maxFailedAttempts = 5;

        /** How long a lockout lasts. */
        private long lockoutMillis = 900_000;

        /** Sliding window over which failures accumulate. */
        private long windowMillis = 900_000;

        /**
         * Also count failures per source address.
         *
         * <p>Worth keeping on: per-username counting alone lets one host walk through many accounts, and
         * per-IP counting alone lets a distributed attack focus on one account.
         */
        private boolean trackByIpAddress = true;

        /** Failures from one address before it is locked out. Higher, since an office shares an IP. */
        private int maxFailedAttemptsPerIp = 25;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxFailedAttempts() {
            return maxFailedAttempts;
        }

        public void setMaxFailedAttempts(int maxFailedAttempts) {
            this.maxFailedAttempts = maxFailedAttempts;
        }

        public long getLockoutMillis() {
            return lockoutMillis;
        }

        public void setLockoutMillis(long lockoutMillis) {
            this.lockoutMillis = lockoutMillis;
        }

        public long getWindowMillis() {
            return windowMillis;
        }

        public void setWindowMillis(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        public boolean isTrackByIpAddress() {
            return trackByIpAddress;
        }

        public void setTrackByIpAddress(boolean trackByIpAddress) {
            this.trackByIpAddress = trackByIpAddress;
        }

        public int getMaxFailedAttemptsPerIp() {
            return maxFailedAttemptsPerIp;
        }

        public void setMaxFailedAttemptsPerIp(int maxFailedAttemptsPerIp) {
            this.maxFailedAttemptsPerIp = maxFailedAttemptsPerIp;
        }
    }

    /** Self-registration. */
    public static class Registration {

        /**
         * Whether anyone may create their own account.
         *
         * <p>Turn it off for an internal deployment where an administrator provisions every account. The
         * endpoint then answers 403 rather than silently accepting and ignoring the request.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Key for reversible secret encryption. Unrelated to passwords, which are never encrypted. */
    public static class Encryption {

        /** Base64-encoded 256-bit AES key. Supplied from the environment; never defaulted. */
        private String key = "";

        /** Recorded on each encrypted value so keys can be rotated without losing old data. */
        private String keyId = "default";

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }
    }
}
