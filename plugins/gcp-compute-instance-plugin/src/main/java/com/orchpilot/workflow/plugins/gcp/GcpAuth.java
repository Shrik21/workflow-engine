package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints and caches Google OAuth2 access tokens from a service-account key — without the Google SDK.
 *
 * <h2>The JWT-bearer flow, in plain JDK</h2>
 *
 * This is the same exchange the Google client library performs: build a short-lived JWT asserting the service
 * account and the Compute scope, sign it RS256 with the account's private key ({@link java.security.Signature}),
 * and POST it to the token endpoint for a bearer access token. Everything here is JDK-native, so the plugin needs
 * no Google dependency — which is what lets it build and run in this environment at all.
 *
 * <h2>Tokens are credentials, so they are never persisted</h2>
 *
 * Access tokens are cached only in memory, keyed by service-account identity, and reused until shortly before they
 * expire. They are never written to the plugin data store, a log, an output, or the model. The token exchange runs
 * over the engine's allow-listed {@link PluginHttpClient}, so the token endpoint host is subject to the same guard
 * as every other call. Thread-safe.
 */
public final class GcpAuth {

    private static final String COMPUTE_SCOPE = "https://www.googleapis.com/auth/compute";
    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    private record CachedToken(String value, long expiresAtEpochSecond) {
        boolean isFresh(long nowEpochSecond) {
            return nowEpochSecond < expiresAtEpochSecond - EXPIRY_SKEW_SECONDS;
        }
    }

    /**
     * @return a valid bearer access token for the credential, minting a new one only when the cache is cold or the
     *         cached token is near expiry
     */
    public String accessToken(GcpCredentials credentials, PluginHttpClient http) {
        long now = System.currentTimeMillis() / 1000;
        CachedToken cached = cache.get(credentials.clientEmail());
        if (cached != null && cached.isFresh(now)) {
            return cached.value();
        }
        CachedToken minted = mint(credentials, http, now);
        cache.put(credentials.clientEmail(), minted);
        return minted.value();
    }

    private CachedToken mint(GcpCredentials credentials, PluginHttpClient http, long nowEpochSecond) {
        String assertion = signedJwt(credentials, nowEpochSecond);
        String form = "grant_type=" + URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        HttpResponseView response = http.execute(HttpRequestSpec.post(credentials.tokenUri(), form)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .build());
        if (!response.isSuccess()) {
            throw new GcpApiException("GCP_AUTHENTICATION_FAILED",
                    "Could not obtain a GCP access token (token endpoint returned HTTP "
                            + response.statusCode() + ").", response.statusCode() >= 500);
        }
        Map<String, Object> body = Json.parseObject(response.body());
        Object token = body.get("access_token");
        if (token == null) {
            throw new GcpApiException("GCP_AUTHENTICATION_FAILED",
                    "The GCP token endpoint returned no access token.", false);
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : TOKEN_LIFETIME_SECONDS;
        return new CachedToken(String.valueOf(token), nowEpochSecond + expiresIn);
    }

    /** Builds and RS256-signs the assertion JWT for the JWT-bearer grant. */
    private String signedJwt(GcpCredentials credentials, long nowEpochSecond) {
        String header = base64Url(Json.write(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = base64Url(Json.write(Map.of(
                "iss", credentials.clientEmail(),
                "scope", COMPUTE_SCOPE,
                "aud", credentials.tokenUri(),
                "iat", nowEpochSecond,
                "exp", nowEpochSecond + TOKEN_LIFETIME_SECONDS)));
        String signingInput = header + "." + claims;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(credentials.privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
            return signingInput + "." + signature;
        } catch (java.security.GeneralSecurityException ex) {
            // No key material in the message.
            throw new GcpApiException("GCP_AUTHENTICATION_FAILED",
                    "Could not sign the GCP authentication assertion.", false);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Test/inspection aid: drops any cached token for a service account. */
    void evict(String clientEmail) {
        cache.remove(clientEmail);
    }
}
