package com.orchpilot.pluginserver.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Everything this service can be configured with, in one place.
 *
 * <p>Bound rather than read through {@code @Value} so that the whole configuration surface is one file a
 * reader can scan, and so that an invalid combination fails at startup rather than on the first request that
 * happens to need it.
 */
@ConfigurationProperties(prefix = "plugin-server")
public class PluginServerProperties {

    private final Registry registry = new Registry();
    private final Security security = new Security();
    private final BootstrapClient bootstrapClient = new BootstrapClient();

    public Registry getRegistry() {
        return registry;
    }

    public Security getSecurity() {
        return security;
    }

    public BootstrapClient getBootstrapClient() {
        return bootstrapClient;
    }

    /**
     * Fails fast on a configuration that cannot work.
     *
     * <p>The alternative, discovering at the first request that no verification key is configured, means the
     * service reports healthy while rejecting every caller with a 401 that looks like their fault.
     */
    @PostConstruct
    void validate() {
        boolean hasSecret = security.getJwtSecret() != null && !security.getJwtSecret().isBlank();
        boolean hasJwks = security.getJwksUri() != null && !security.getJwksUri().isBlank();

        if (!hasSecret && !hasJwks) {
            throw new IllegalStateException("""
                    The plugin server cannot verify tokens: neither plugin-server.security.jwt-secret nor \
                    plugin-server.security.jwks-uri is set.

                    Set PLUGIN_SERVER_JWT_SECRET to the same secret the workflow platform signs with, or \
                    PLUGIN_SERVER_JWKS_URI to its public key set. Prefer the JWKS form for anything shared: \
                    this service then only verifies tokens and holds no ability to mint them.""");
        }
        if (hasSecret && hasJwks) {
            throw new IllegalStateException("""
                    Both plugin-server.security.jwt-secret and plugin-server.security.jwks-uri are set, and \
                    they imply different trust models. Choose one: a shared symmetric secret, or a public key \
                    set this service only verifies against.""");
        }
        if (hasSecret && security.getJwtSecret().trim().length() < 32) {
            // HS256 with a short key is a key that can be brute-forced, and a token forged against this
            // service is an upload credential for executable code.
            throw new IllegalStateException("plugin-server.security.jwt-secret must be at least 32 "
                    + "characters. Generate one with: openssl rand -base64 48");
        }
        if (registry.getMaxJarSize().toBytes() <= 0) {
            throw new IllegalStateException("plugin-server.registry.max-jar-size must be positive.");
        }
    }

    /** Registry behaviour. */
    public static class Registry {

        private DataSize maxJarSize = DataSize.ofMegabytes(64);
        private boolean publishOnUpload;

        public DataSize getMaxJarSize() {
            return maxJarSize;
        }

        public void setMaxJarSize(DataSize maxJarSize) {
            this.maxJarSize = maxJarSize;
        }

        /**
         * @return whether an uploaded version becomes ACTIVE immediately, rather than landing in DRAFT for a
         *         separate publish step
         */
        public boolean isPublishOnUpload() {
            return publishOnUpload;
        }

        public void setPublishOnUpload(boolean publishOnUpload) {
            this.publishOnUpload = publishOnUpload;
        }
    }

    /** Token verification and archive signing. */
    public static class Security {

        private String jwtSecret;
        private String jwksUri;
        private String issuer = "orchpilot-workflow";
        private final Signature signature = new Signature();

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public Signature getSignature() {
            return signature;
        }

        /** @return whether tokens are verified with a shared symmetric secret rather than a public key set */
        public boolean isSymmetric() {
            return jwtSecret != null && !jwtSecret.isBlank();
        }
    }

    /**
     * The service client registered on first start.
     *
     * <p>Breaks a bootstrap loop: registering a client needs an admin token, an admin token comes from the
     * workflow platform, and the workflow platform needs a client to sync. One set of configured values, applied
     * only when the client does not already exist, is enough to get a fresh installation running.
     */
    public static class BootstrapClient {

        private String clientId = "";
        private String clientSecret = "";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId == null ? "" : clientId.trim();
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret == null ? "" : clientSecret;
        }

        /** @return whether both halves are present, so there is something to register */
        public boolean isConfigured() {
            return !clientId.isBlank() && !clientSecret.isBlank();
        }
    }

    /** Optional JAR signature verification. */
    public static class Signature {

        private boolean verificationEnabled;
        private String truststore;

        public boolean isVerificationEnabled() {
            return verificationEnabled;
        }

        public void setVerificationEnabled(boolean verificationEnabled) {
            this.verificationEnabled = verificationEnabled;
        }

        public String getTruststore() {
            return truststore;
        }

        public void setTruststore(String truststore) {
            this.truststore = truststore;
        }
    }
}
