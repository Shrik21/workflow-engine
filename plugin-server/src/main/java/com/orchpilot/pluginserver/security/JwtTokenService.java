package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.user.User;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues this registry's own access tokens.
 *
 * <h2>Independent of every other service</h2>
 *
 * These tokens are signed with this registry's key, name this registry as issuer, and carry this registry's
 * roles and permissions. Nothing about them depends on the workflow platform, and a token minted by the
 * platform is not accepted here — which is the entire point of the registry having its own accounts. The
 * registry decides who may publish executable code to it.
 *
 * <h2>RS256 where it matters</h2>
 *
 * With a key pair configured this service signs with the private key, and anything that needs to verify can be
 * given only the public one. Symmetric signing is offered for a laptop, where the only holder is this process,
 * and is refused for anything shared: a shared HMAC secret means every verifier can also forge.
 *
 * <h2>The claims are deliberately boring</h2>
 *
 * Subject, username, roles, permissions, issued-at, expiry, and a token id. No email, no name, nothing about
 * the person beyond what an authorisation decision needs. A JWT is readable by anyone holding it.
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /** Minimum length for a symmetric secret. HS256 uses SHA-256, so a shorter key weakens every signature. */
    private static final int MIN_HMAC_BYTES = 32;

    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PERMISSIONS = "permissions";
    /** Marks a machine account, so a service token can never be mistaken for a person's. */
    public static final String CLAIM_SERVICE = "service";

    private final AuthProperties properties;
    private final JwtEncoder encoder;
    private final boolean asymmetric;
    private final RSAPublicKey publicKey;
    private final SecretKeySpec hmacKey;

    public JwtTokenService(AuthProperties properties) {
        this.properties = properties;
        AuthProperties.Jwt jwt = properties.getJwt();

        if (jwt.hasRsaKeys()) {
            KeyPair pair = readKeyPair(jwt.getPrivateKey(), jwt.getPublicKey());
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.encoder = rsaEncoder(this.publicKey, (RSAPrivateKey) pair.getPrivate());
            this.hmacKey = null;
            this.asymmetric = true;
            log.info("Signing access tokens with RS256 using the configured key pair");
        } else if (!jwt.getHmacSecret().isBlank()) {
            this.hmacKey = hmacKey(jwt.getHmacSecret());
            this.encoder = new NimbusJwtEncoder(
                    new ImmutableJWKSet<>(new JWKSet(new com.nimbusds.jose.jwk.OctetSequenceKey.Builder(
                            hmacKey.getEncoded()).build())));
            this.publicKey = null;
            this.asymmetric = false;
            log.warn("Signing access tokens with HS256. The signing key can also verify, so anything given "
                    + "it can forge tokens. Configure an RSA key pair for anything beyond development.");
        } else if (jwt.isAllowEphemeralKey()) {
            KeyPair pair = generateKeyPair();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.encoder = rsaEncoder(this.publicKey, (RSAPrivateKey) pair.getPrivate());
            this.hmacKey = null;
            this.asymmetric = true;
            log.warn("No JWT signing key is configured, so one was generated for this run. Every token dies "
                    + "when this process does, and no other instance can verify them. Set "
                    + "plugin-server.auth.jwt.private-key and public-key before deploying.");
        } else {
            throw new IllegalStateException(
                    "No JWT signing key is configured and ephemeral keys are not allowed. Set "
                            + "plugin-server.auth.jwt.private-key and plugin-server.auth.jwt.public-key, "
                            + "or supply plugin-server.auth.jwt.hmac-secret for development.");
        }
    }

    /**
     * Mints an access token for a signed-in account.
     *
     * @param user        the account
     * @param roles       its role names
     * @param permissions its effective permissions
     * @return the token and when it expires
     */
    public IssuedToken issue(User user, Set<String> roles, Set<String> permissions) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getJwt().getAccessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(user.getId())
                .issuedAt(now)
                .expiresAt(expiry)
                // A unique id per token, so one can be named in an audit entry without recording the token.
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_ROLES, List.copyOf(roles))
                .claim(CLAIM_PERMISSIONS, List.copyOf(permissions))
                .claim(CLAIM_SERVICE, user.isServiceAccount())
                .build();

        JwsHeader header = asymmetric
                ? JwsHeader.with(SignatureAlgorithm.RS256).build()
                : JwsHeader.with(MacAlgorithm.HS256).build();

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new IssuedToken(jwt.getTokenValue(), expiry,
                properties.getJwt().getAccessTokenTtl().toSeconds());
    }

    /**
     * Mints a token for a machine identity.
     *
     * <p>Signed with the same key as a person's token, and deliberately shaped differently: it carries no
     * roles, only the permissions the client is registered for, and it is marked as a service token. A machine
     * identity cannot claim to be a person, and a leaked service token cannot be mistaken for one.
     *
     * @param clientId    the registered client
     * @param permissions what it may do
     * @param ttl         how long the token lives
     * @return the token
     */
    public IssuedToken issueForClient(String clientId, Set<String> permissions, java.time.Duration ttl) {
        Instant now = Instant.now();
        Instant expiry = now.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(clientId)
                .issuedAt(now)
                .expiresAt(expiry)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USERNAME, clientId)
                .claim(CLAIM_PERMISSIONS, List.copyOf(permissions))
                // Space-delimited as well, because that is what the client-credentials convention expects and
                // what an operator reading a token will look for.
                .claim("scope", String.join(" ", permissions))
                .claim("client_id", clientId)
                .claim(CLAIM_SERVICE, true)
                .build();

        JwsHeader header = asymmetric
                ? JwsHeader.with(SignatureAlgorithm.RS256).build()
                : JwsHeader.with(MacAlgorithm.HS256).build();

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(header, claims));
        return new IssuedToken(jwt.getTokenValue(), expiry, ttl.toSeconds());
    }

    /** @return the public key, for a JWKS endpoint; null when signing symmetrically */
    public RSAPublicKey verificationKey() {
        return publicKey;
    }

    /** @return whether tokens are signed asymmetrically */
    public boolean isAsymmetric() {
        return asymmetric;
    }

    /** @return the symmetric key, for the decoder; null when signing asymmetrically */
    SecretKeySpec symmetricKey() {
        return hmacKey;
    }

    /**
     * A minted token.
     *
     * @param value     the encoded JWT
     * @param expiresAt when it stops being accepted
     * @param expiresIn its lifetime in seconds, which is what a client reports and schedules against
     */
    public record IssuedToken(String value, Instant expiresAt, long expiresIn) {
    }

    private static JwtEncoder rsaEncoder(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        JWK jwk = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(source);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("RSA key generation is unavailable in this JVM", ex);
        }
    }

    private static KeyPair readKeyPair(String privatePem, String publicPem) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(strip(privatePem))));
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getMimeDecoder().decode(strip(publicPem))));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "plugin-server.auth.jwt.private-key and public-key must be a matching PEM RSA pair", ex);
        }
    }

    /** Strips the PEM armour, leaving the Base64 body. */
    private static String strip(String pem) {
        return pem.replaceAll("-----(BEGIN|END)[^-]+-----", "").replaceAll("\\s", "");
    }

    /**
     * Derives the symmetric key.
     *
     * <p>Base64 first, falling back to the raw bytes, so a secret generated with {@code openssl rand -base64}
     * and one typed as a passphrase both work. Deriving differently from another service is how two systems
     * configured with the same secret end up unable to verify each other's tokens.
     */
    private static SecretKeySpec hmacKey(String secret) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException ex) {
            raw = secret.trim().getBytes(StandardCharsets.UTF_8);
        }
        if (raw.length < MIN_HMAC_BYTES) {
            throw new IllegalStateException("plugin-server.auth.jwt.hmac-secret decodes to " + raw.length
                    + " bytes; at least " + MIN_HMAC_BYTES + " are required for HS256.");
        }
        return new SecretKeySpec(raw, "HmacSHA256");
    }
}
