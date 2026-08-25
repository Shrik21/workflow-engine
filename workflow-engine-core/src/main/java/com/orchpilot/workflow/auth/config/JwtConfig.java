package com.orchpilot.workflow.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * JWT signing and verification keys.
 *
 * <p>Two supported algorithms, selected by {@code security.jwt.algorithm}:
 *
 * <p><b>HS256</b> is a MAC: one secret both signs and verifies. Anything that can verify a token can
 * also mint one, so it is only appropriate while a single service issues and consumes its own tokens,
 * which is how this platform ships.
 *
 * <p><b>RS256</b> is a signature: the private key signs and the public key only verifies. A second
 * service can validate tokens without holding anything that lets it forge one, and the public half can be
 * published at a JWK set endpoint and rotated without redeploying consumers. Switch to it the moment
 * these tokens cross a service boundary.
 *
 * <p>The decoder is pinned to the configured algorithm rather than trusting the token's own {@code alg}
 * header. That is what defeats algorithm-confusion attacks, where an attacker re-signs an RS256 token
 * with HS256 using the public key as the HMAC secret, or presents {@code alg: none}. Nimbus will reject
 * any token whose algorithm is not the expected one.
 *
 * <p>Every key comes from configuration and therefore from the environment. There is no default secret:
 * the application refuses to start without one, because a shipped signing key is equivalent to no
 * authentication at all.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    /**
     * Minimum HMAC secret length. HS256 uses SHA-256 internally, so a key shorter than its 256-bit block
     * adds nothing and a short, guessable secret undermines the signature entirely.
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * The development-only ephemeral key, generated at most once per process.
     *
     * <p>Static because the encoder and decoder beans must receive the same key, and there is exactly one
     * signing key per process by definition.
     */
    private static volatile SecretKeySpec ephemeralSecret;

    /**
     * What a token is signed with, so the encoder and the JWKS endpoint agree.
     *
     * @param algorithm JWS algorithm name, {@code HS256} or {@code RS256}
     * @param keyId     key id for the token header, or {@code null} under HS256
     */
    public record SigningDetails(String algorithm, String keyId) {
    }

    @Bean
    public SigningDetails jwtSigningDetails(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.getJwt();
        String algorithm = jwt.isAsymmetric() ? "RS256" : "HS256";
        /*
         * The key id is carried only for RS256. Nimbus selects a signing key by matching the header's kid
         * against the JWK set, and the OctetSequenceKey that ImmutableSecret builds for an HMAC secret has
         * no kid: naming one in the header would match nothing and encoding would fail with
         * "Failed to select a JWK signing key". A kid is also of no use under HS256, where there is
         * nothing to publish and only one key can ever verify.
         */
        String headerKeyId = jwt.isAsymmetric() ? jwt.getKeyId() : null;
        log.info("JWT access tokens are signed with {} (issuer '{}', lifetime {}s)",
                algorithm, jwt.getIssuer(), jwt.getAccessTokenExpiration() / 1000);
        if (!jwt.isAsymmetric()) {
            log.info("HS256 uses one shared secret for both signing and verification, which is correct "
                    + "for a single service. Move to RS256 before another service validates these tokens.");
        }
        return new SigningDetails(algorithm, headerKeyId);
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.getJwt();
        if (jwt.isAsymmetric()) {
            RSAKey rsaKey = new RSAKey.Builder(readPublicKey(jwt.getPublicKey()))
                    .privateKey(readPrivateKey(jwt.getPrivateKey()))
                    .keyID(jwt.getKeyId())
                    .build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        }
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(hmacKey(jwt)));
    }

    @Bean
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.getJwt();
        NimbusJwtDecoder decoder;
        if (jwt.isAsymmetric()) {
            decoder = NimbusJwtDecoder.withPublicKey(readPublicKey(jwt.getPublicKey()))
                    // Pinned: a token arriving as HS256 is rejected rather than verified with the
                    // public key as an HMAC secret.
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } else {
            decoder = NimbusJwtDecoder.withSecretKey(hmacKey(jwt))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        }
        // Validates expiry, not-before and the issuer claim. A token minted by a different deployment
        // sharing the same secret is therefore still rejected.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwt.getIssuer()));
        return decoder;
    }

    /**
     * The public JWK set, exposed only for RS256.
     *
     * <p>Conditional on purpose: publishing anything derived from an HMAC secret would hand out the key
     * that mints tokens. There is no safe HS256 equivalent of a JWKS endpoint.
     *
     * @return the public JWK set, or {@code null} when signing symmetrically
     */
    @Bean
    public JWKSet publicJwkSet(AuthProperties properties) {
        AuthProperties.Jwt jwt = properties.getJwt();
        if (!jwt.isAsymmetric()) {
            return null;
        }
        RSAKey publicOnly = new RSAKey.Builder(readPublicKey(jwt.getPublicKey()))
                .keyID(jwt.getKeyId())
                .build()
                .toPublicJWK();
        return new JWKSet(publicOnly);
    }

    private static SecretKeySpec hmacKey(AuthProperties.Jwt jwt) {
        String secret = jwt.getSecret();

        if (secret == null || secret.isBlank()) {
            if (jwt.isAllowEphemeralSecret()) {
                return ephemeralKey();
            }
            throw new MissingSecurityKeyException(
                    "security.jwt.secret", "JWT_SECRET", "openssl rand -base64 48",
                    "No JWT signing secret is configured, so access tokens cannot be signed or verified.");
        }

        byte[] raw = decodeSecret(secret.trim());
        if (raw.length < MIN_SECRET_BYTES) {
            throw new MissingSecurityKeyException(
                    "security.jwt.secret", "JWT_SECRET", "openssl rand -base64 48",
                    "The JWT signing secret decodes to " + raw.length + " bytes, but at least "
                            + MIN_SECRET_BYTES + " are required. HS256 uses SHA-256 internally, so a shorter "
                            + "key weakens every signature it produces.");
        }
        return new SecretKeySpec(raw, "HmacSHA256");
    }

    /**
     * A random signing key, generated once per start, for local development only.
     *
     * <p>Enabled by the {@code dev} profile. Random rather than a committed constant, because a hardcoded
     * development secret invariably reaches production: it would be in the repository, in every image, and
     * identical everywhere.
     *
     * <p>The consequence is stated loudly at startup, because it is surprising: every token becomes invalid
     * when the process restarts, and two instances cannot verify each other's tokens. That is an availability
     * problem rather than a confidentiality one, but it is exactly the sort of thing that gets diagnosed for
     * an hour before someone checks which profile is active.
     */
    private static SecretKeySpec ephemeralKey() {
        // Generated once and shared. The encoder and the decoder are separate beans that each ask for the
        // key, so generating per call would leave the decoder unable to verify the encoder's own tokens:
        // every request would answer 401 and the cause would be invisible.
        SecretKeySpec existing = ephemeralSecret;
        if (existing != null) {
            return existing;
        }
        synchronized (JwtConfig.class) {
            if (ephemeralSecret == null) {
                byte[] raw = new byte[64];
                new java.security.SecureRandom().nextBytes(raw);
                ephemeralSecret = new SecretKeySpec(raw, "HmacSHA256");
                log.warn("""
                        No JWT secret is configured, so an ephemeral one was generated for this process.
                          * Every access token becomes invalid when this process restarts.
                          * A second instance cannot verify tokens issued by this one.
                          * Never use this outside local development. Set JWT_SECRET instead.""");
            }
            return ephemeralSecret;
        }
    }

    /**
     * Accepts either a Base64 secret or a raw passphrase.
     *
     * <p>Base64 is preferred and is what the documented generator produces, but rejecting a long random
     * passphrase would be pedantic. The length check afterwards applies to the decoded bytes either way,
     * so neither form can be short.
     */
    private static byte[] decodeSecret(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static RSAPublicKey readPublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.public-key is required when security.jwt.algorithm is RS256");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(stripPem(pem));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("security.jwt.public-key is not a valid X.509 RSA public key", ex);
        }
    }

    private static RSAPrivateKey readPrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(
                    "security.jwt.private-key is required when security.jwt.algorithm is RS256");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(stripPem(pem));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "security.jwt.private-key is not a valid PKCS#8 RSA private key", ex);
        }
    }

    /** Tolerates a PEM with or without its armour, and with any line endings. */
    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
    }
}
