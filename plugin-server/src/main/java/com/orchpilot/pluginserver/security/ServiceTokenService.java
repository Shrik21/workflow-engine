package com.orchpilot.pluginserver.security;

import com.orchpilot.pluginserver.config.PluginServerProperties;
import com.orchpilot.pluginserver.exception.PluginServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Issues short-lived tokens to registered service clients.
 *
 * <h2>Client credentials, in the shape this platform can actually verify</h2>
 *
 * <p>A workflow service presents a client id and secret and receives a JWT carrying only the authorities its
 * client is registered for. It is the client-credentials grant without an OAuth2 authorization server: the
 * platform already has a JWT verification story, and adding a second identity system to gain a discovery document
 * nothing reads would be cost without benefit. The properties that matter are present. The secret never travels
 * except on the token request, tokens are short-lived, and revoking a client is one field on one document rather
 * than a secret rotation across every service.
 *
 * <h2>Why this can be unavailable</h2>
 *
 * <p>Issuing requires signing. When the registry is configured with a JWKS URI it can only verify, which is the
 * recommended posture precisely because it means a compromise here cannot mint tokens for anything. In that mode
 * this endpoint refuses with an explanation rather than pretending: the workflow service should get its token from
 * whatever issues the key set instead.
 */
@Service
public class ServiceTokenService {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenService.class);

    /**
     * How long a service token lasts.
     *
     * <p>Long enough that a sync interval does not spend most of its time authenticating, short enough that a
     * leaked token is a problem for minutes rather than months. A client that gets a 401 asks for another.
     */
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

    private final ServiceClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final PluginServerProperties properties;
    private final JwtTokenService tokens;

    public ServiceTokenService(ServiceClientRepository clients, PasswordEncoder passwordEncoder,
                              PluginServerProperties properties, JwtTokenService tokens) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.tokens = tokens;
    }

    /**
     * An issued token.
     *
     * @param accessToken the JWT
     * @param tokenType   always {@code Bearer}
     * @param expiresIn   lifetime in seconds
     * @param scope       space-delimited authorities, as the client credentials convention expects
     */
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String scope) {
    }

    /**
     * Authenticates a client and issues a token.
     *
     * @param clientId     the client
     * @param clientSecret its secret
     * @return the token
     * @throws PluginServerException 401 when the credentials are wrong, 501 when this registry cannot sign
     */
    public TokenResponse issue(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw PluginServerException.invalidClient();
        }

        ServiceClient client = clients.findById(clientId.trim()).orElse(null);

        if (client == null) {
            /*
             * Verify against a dummy hash before refusing.
             *
             * Without it an unknown client id returns in microseconds while a known one pays for a BCrypt
             * comparison, and that difference is a timing oracle for enumerating valid client ids. The wasted
             * work is the entire point of this call.
             */
            passwordEncoder.matches(clientSecret, DUMMY_HASH);
            log.info("Refused a token request for unknown client '{}'", clientId);
            throw PluginServerException.invalidClient();
        }
        if (!passwordEncoder.matches(clientSecret, client.getSecretHash())) {
            log.info("Refused a token request for client '{}': wrong secret", clientId);
            throw PluginServerException.invalidClient();
        }
        if (!client.isEnabled()) {
            // Same answer as a wrong secret: whether a client exists but is disabled is not the caller's
            // business, and saying so would confirm the id.
            log.warn("Refused a token request for disabled client '{}'", clientId);
            throw PluginServerException.invalidClient();
        }
        if (client.getAuthorities().isEmpty()) {
            throw PluginServerException.forbidden("CLIENT_HAS_NO_AUTHORITIES",
                    "Client '" + clientId + "' is registered with no authorities, so a token for it would "
                            + "grant nothing.");
        }

        Instant now = Instant.now();
        String scope = String.join(" ", client.authorityNames());

        /*
         * Signed by the registry's own signer, the same one that mints a person's token.
         *
         * Service tokens used to be signed with a separate secret, back when user tokens did not exist.
         * Two signers would now mean two keys to rotate and one of them silently failing to verify.
         */
        String token = tokens.issueForClient(client.getClientId(),
                new java.util.LinkedHashSet<>(client.authorityNames()), TOKEN_LIFETIME).value();

        client.setLastUsedAt(now);
        client.setTokensIssued(client.getTokensIssued() + 1);
        clients.save(client);

        log.info("Issued a {}-minute token to service client '{}' with scope [{}]",
                TOKEN_LIFETIME.toMinutes(), client.getClientId(), scope);
        return new TokenResponse(token, "Bearer", TOKEN_LIFETIME.toSeconds(), scope);
    }

    /**
     * A real BCrypt digest of a value nothing knows, so an unknown client id costs the same as a known one.
     *
     * <p>Of the string "no-such-client", which is irrelevant: what matters is that it is a well-formed digest at
     * the same cost factor, so verification does the same work.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
