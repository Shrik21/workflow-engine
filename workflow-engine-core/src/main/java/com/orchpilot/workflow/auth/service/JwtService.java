package com.orchpilot.workflow.auth.service;

import com.orchpilot.workflow.auth.config.AuthProperties;
import com.orchpilot.workflow.auth.config.JwtConfig;
import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and validates access tokens.
 *
 * <p><b>What goes in a token, and what does not.</b> The claims are the user id, username, roles, issued
 * and expiry times, issuer and a token id. Nothing else. No email, no password hash, no API key, no
 * permission list. A JWT is signed, not encrypted: anyone holding it can read every claim, so a claim is
 * a disclosure to whoever has the token.
 *
 * <p>Permissions are deliberately absent even though they drive authorization. They are derived from the
 * user's current roles on each request, so revoking a permission takes effect immediately instead of
 * waiting out the token's remaining lifetime. Roles are included only as useful context for the client;
 * the server re-derives authorities from the database and never trusts the token's copy.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Claim carrying the username, alongside the standard {@code sub} which carries the user id. */
    public static final String CLAIM_USERNAME = "username";

    /** Claim carrying role names, for the client's convenience only. */
    public static final String CLAIM_ROLES = "roles";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtConfig.SigningDetails signing;
    private final AuthProperties.Jwt properties;

    public JwtService(JwtEncoder encoder, JwtDecoder decoder, JwtConfig.SigningDetails signing,
                      AuthProperties properties) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.signing = signing;
        this.properties = properties.getJwt();
    }

    /**
     * Issues an access token for a user.
     *
     * @param user the authenticated user
     * @return a signed compact JWT
     */
    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(properties.getAccessTokenExpiration());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(user.getId())
                .issuedAt(now)
                .expiresAt(expiry)
                // A token id makes an individual token identifiable in an audit trail without
                // recording the token itself.
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_ROLES, user.getRoles().stream().map(Enum::name).toList())
                .build();

        JwsHeader.Builder header = JwsHeader.with(() -> signing.algorithm());
        // Only under RS256, where the JWK set actually contains a key with this id. See JwtConfig.
        if (signing.keyId() != null) {
            header.keyId(signing.keyId());
        }

        return encoder.encode(JwtEncoderParameters.from(header.build(), claims)).getTokenValue();
    }

    /**
     * Validates a token and extracts its identity.
     *
     * <p>Validation covers the signature, the pinned algorithm, expiry, not-before and the issuer. It
     * does not cover whether the account still exists or is still enabled: that requires the database and
     * is the caller's job, which is why this returns an identity rather than an authenticated principal.
     *
     * @param token compact JWT from the Authorization header
     * @return the token's identity, or empty when the token is invalid for any reason
     */
    public Optional<TokenIdentity> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            return Optional.of(new TokenIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString(CLAIM_USERNAME),
                    readRoles(jwt),
                    jwt.getId(),
                    jwt.getExpiresAt()));
        } catch (JwtException ex) {
            // Logged at debug and without the token: an expired token is a routine event, and the
            // token value must never reach a log file.
            log.debug("Rejected an access token: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** @return access token lifetime in seconds, for the {@code expiresIn} field of a login response */
    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenExpiration() / 1000;
    }

    /** @return refresh token lifetime */
    public Instant refreshTokenExpiry(Instant from) {
        return from.plus(properties.getRefreshTokenExpiration(), ChronoUnit.MILLIS);
    }

    private static Set<Role> readRoles(Jwt jwt) {
        List<String> names = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (names == null) {
            return Set.of();
        }
        Set<Role> roles = new LinkedHashSet<>();
        for (String name : names) {
            Role.parse(name).ifPresent(roles::add);
        }
        return roles;
    }

    /**
     * The identity asserted by a validated token.
     *
     * @param userId    subject claim
     * @param username  username claim
     * @param roles     roles claimed by the token, used as context rather than as authority
     * @param tokenId   the {@code jti} claim
     * @param expiresAt expiry
     */
    public record TokenIdentity(String userId, String username, Set<Role> roles, String tokenId,
                                Instant expiresAt) {
    }
}
