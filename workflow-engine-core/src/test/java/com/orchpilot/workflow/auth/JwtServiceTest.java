package com.orchpilot.workflow.auth;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.config.JwtConfig;
import com.orchpilot.workflow.auth.config.MissingSecurityKeyException;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import com.orchpilot.workflow.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access token issuing and validation.
 *
 * <p>The claim assertions matter as much as the round trip. A JWT is signed but not encrypted, so anyone
 * holding one can read every claim; a test that only checks the token parses would not notice a refactor
 * that started including an email address or a permission list.
 */
class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "a-test-signing-secret-that-is-long-enough-for-hs256".getBytes());

    private static AuthProperties hs256() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setAlgorithm("HS256");
        properties.getJwt().setSecret(SECRET);
        properties.getJwt().setIssuer("workflow-platform");
        return properties;
    }

    private static JwtService serviceFor(AuthProperties properties) {
        JwtConfig config = new JwtConfig();
        JwtEncoder encoder = config.jwtEncoder(properties);
        JwtDecoder decoder = config.jwtDecoder(properties);
        return new JwtService(encoder, decoder, config.jwtSigningDetails(properties), properties);
    }

    private static User user() {
        User user = new User();
        user.setId("user-123");
        user.setUsername("vivek");
        user.setEmail("vivek@example.com");
        user.setPasswordHash("{argon2}$argon2id$never-in-a-token");
        user.setRoles(Set.of(Role.USER));
        return user;
    }

    @Test
    @DisplayName("round-trips identity through a signed token")
    void roundTrips() {
        JwtService jwt = serviceFor(hs256());
        String token = jwt.issueAccessToken(user());

        var identity = jwt.parse(token);
        assertThat(identity).isPresent();
        assertThat(identity.get().userId()).isEqualTo("user-123");
        assertThat(identity.get().username()).isEqualTo("vivek");
        assertThat(identity.get().roles()).containsExactly(Role.USER);
        assertThat(identity.get().tokenId()).isNotBlank();
    }

    @Test
    @DisplayName("carries no email, password hash or permission list")
    void carriesOnlyNecessaryClaims() {
        JwtService jwt = serviceFor(hs256());
        String token = jwt.issueAccessToken(user());

        // The payload is Base64url and readable by anyone holding the token, so inspect it directly.
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));

        assertThat(payload).contains("user-123", "vivek", "USER", "workflow-platform");
        assertThat(payload).doesNotContain("vivek@example.com");
        assertThat(payload).doesNotContain("argon2");
        assertThat(payload).doesNotContain("passwordHash");
        // Permissions are derived per request from current roles, so revoking one takes effect at once
        // instead of waiting out the token.
        assertThat(payload).doesNotContain("WORKFLOW_CREATE");
    }

    @Test
    @DisplayName("rejects a tampered token")
    void rejectsTamperedToken() {
        JwtService jwt = serviceFor(hs256());
        String token = jwt.issueAccessToken(user());

        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]))
                        .replace("\"USER\"", "\"ADMIN\"").getBytes());

        assertThat(jwt.parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForeignSignature() {
        JwtService issuer = serviceFor(hs256());
        String token = issuer.issueAccessToken(user());

        AuthProperties other = hs256();
        other.getJwt().setSecret(Base64.getEncoder().encodeToString(
                "a-completely-different-secret-of-sufficient-length".getBytes()));

        assertThat(serviceFor(other).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("rejects a token from a different issuer")
    void rejectsForeignIssuer() {
        AuthProperties minting = hs256();
        minting.getJwt().setIssuer("some-other-platform");
        String token = serviceFor(minting).issueAccessToken(user());

        // Same secret, different issuer: another deployment sharing a secret must still be rejected.
        assertThat(serviceFor(hs256()).parse(token)).isEmpty();
    }

    @Test
    @DisplayName("rejects an expired token")
    void rejectsExpiredToken() throws InterruptedException {
        AuthProperties properties = hs256();
        properties.getJwt().setAccessTokenExpiration(1);
        JwtService jwt = serviceFor(properties);

        String token = jwt.issueAccessToken(user());
        Thread.sleep(1100);

        assertThat(jwt.parse(token)).isEmpty();
    }

    @Test
    @DisplayName("rejects rubbish without throwing")
    void rejectsGarbage() {
        JwtService jwt = serviceFor(hs256());

        assertThat(jwt.parse(null)).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
        assertThat(jwt.parse("not.a.token")).isEmpty();
        // alg:none, the classic attack. Nimbus is pinned to HS256 and refuses it.
        assertThat(jwt.parse("eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbiJ9.")).isEmpty();
    }

    @Test
    @DisplayName("refuses to start with a missing or short signing secret")
    void requiresStrongSecret() {
        AuthProperties none = hs256();
        none.getJwt().setSecret("");
        assertThatThrownBy(() -> serviceFor(none))
                // A dedicated type, so the failure analyzer can print an actionable message rather than a
                // nested bean-creation stack trace.
                .isInstanceOf(MissingSecurityKeyException.class)
                .hasMessageContaining("security.jwt.secret")
                .hasMessageContaining("JWT_SECRET");

        AuthProperties tooShort = hs256();
        tooShort.getJwt().setSecret("short");
        assertThatThrownBy(() -> serviceFor(tooShort))
                .isInstanceOf(MissingSecurityKeyException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    @DisplayName("generates one shared ephemeral key when the dev profile allows it")
    void ephemeralSecretIsSharedBetweenEncoderAndDecoder() {
        AuthProperties dev = new AuthProperties();
        dev.getJwt().setSecret("");
        dev.getJwt().setAllowEphemeralSecret(true);
        dev.getJwt().setIssuer("workflow-platform");

        JwtService jwt = serviceFor(dev);
        String token = jwt.issueAccessToken(user());

        // The encoder and decoder are separate beans that each ask for the key. If a fresh key were
        // generated per call, the decoder could not verify the encoder's own tokens and every request would
        // answer 401 for no visible reason.
        assertThat(jwt.parse(token)).isPresent();
    }

    @Test
    @DisplayName("works with RS256, so a token can be verified without the signing key")
    void supportsRs256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        AuthProperties properties = new AuthProperties();
        properties.getJwt().setAlgorithm("RS256");
        properties.getJwt().setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.getJwt().setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        properties.getJwt().setIssuer("workflow-platform");

        JwtService jwt = serviceFor(properties);
        String token = jwt.issueAccessToken(user());

        assertThat(jwt.parse(token)).isPresent();
        assertThat(new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]))).contains("RS256");

        // The public JWK set is published only for RS256; there is no safe HS256 equivalent.
        assertThat(new JwtConfig().publicJwkSet(properties)).isNotNull();
        assertThat(new JwtConfig().publicJwkSet(hs256())).isNull();
    }
}
