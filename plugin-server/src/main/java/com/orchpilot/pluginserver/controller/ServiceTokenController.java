package com.orchpilot.pluginserver.controller;

import com.orchpilot.pluginserver.exception.PluginServerException;
import com.orchpilot.pluginserver.security.ServiceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Where a workflow service gets its token.
 *
 * <h2>Two ways to present credentials</h2>
 *
 * <p>HTTP Basic, which is what the client-credentials convention prefers and what {@code RestClient} makes easiest,
 * or form fields. Both are accepted because insisting on one would mean every client author reads this class to
 * find out which.
 *
 * <p>The response is never cached and never logged. It is deliberately not a GET: a token in a URL ends up in
 * access logs, browser history and proxy caches, and the fact that it would be more convenient is the reason
 * people do it.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Service tokens", description = "Client-credentials tokens for service-to-service calls")
public class ServiceTokenController {

    private final ServiceTokenService tokens;

    public ServiceTokenController(ServiceTokenService tokens) {
        this.tokens = tokens;
    }

    /**
     * Exchanges a client id and secret for a short-lived token.
     *
     * @param grantType     must be {@code client_credentials}
     * @param formClientId  client id, when sent as a form field
     * @param formSecret    client secret, when sent as a form field
     * @param authorization HTTP Basic credentials, when sent that way instead
     * @return the token
     */
    @PostMapping(path = "/token",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(summary = "Get a service token",
            description = """
                    The client-credentials grant. Present the client id and secret with HTTP Basic, or as \
                    client_id and client_secret form fields.

                    The token carries only the authorities the client is registered for, lasts fifteen minutes, \
                    and names no roles: a service token cannot claim to be a person.

                    Answers 501 when this registry is configured to verify tokens against a key set rather than a \
                    shared secret, because it then holds no signing key. Get the token from whatever publishes \
                    that key set instead.""")
    @ApiResponse(responseCode = "200", description = "A token")
    @ApiResponse(responseCode = "401", description = "The client id or secret is not valid")
    @ApiResponse(responseCode = "501", description = "This registry cannot issue tokens")
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam(value = "grant_type", required = false) String grantType,
            @RequestParam(value = "client_id", required = false) String formClientId,
            @RequestParam(value = "client_secret", required = false) String formSecret,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {

        if (grantType != null && !grantType.isBlank() && !"client_credentials".equals(grantType)) {
            throw PluginServerException.badRequest("UNSUPPORTED_GRANT_TYPE",
                    "Only the client_credentials grant is supported.");
        }

        String[] basic = decodeBasic(authorization);
        String clientId = basic != null ? basic[0] : formClientId;
        String clientSecret = basic != null ? basic[1] : formSecret;

        ServiceTokenService.TokenResponse issued = tokens.issue(clientId, clientSecret);

        return ResponseEntity.ok()
                // Belt and braces against an intermediary that would otherwise be free to store this.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(Map.of(
                        "access_token", issued.accessToken(),
                        "token_type", issued.tokenType(),
                        "expires_in", issued.expiresIn(),
                        "scope", issued.scope()));
    }

    /**
     * Reads HTTP Basic credentials.
     *
     * @param authorization the header value
     * @return {@code [clientId, secret]}, or null when the header is absent or not Basic
     */
    private static String[] decodeBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 0) {
                return null;
            }
            // Split on the first colon only: a secret may legitimately contain one.
            return new String[]{decoded.substring(0, separator), decoded.substring(separator + 1)};
        } catch (IllegalArgumentException ex) {
            // Malformed base64. Treated as absent, so the caller gets the same INVALID_CLIENT as any other
            // credential failure rather than a hint that the header parsed.
            return null;
        }
    }
}
