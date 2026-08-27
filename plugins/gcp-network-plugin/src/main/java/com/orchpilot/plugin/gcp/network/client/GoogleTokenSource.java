package com.orchpilot.plugin.gcp.network.client;

import com.orchpilot.plugin.gcp.network.exception.GcpNetworkException;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints and caches Google OAuth2 access tokens from a service-account key, using only the JDK.
 *
 * <h2>One scope, every resource</h2>
 *
 * {@code cloud-platform} covers every network resource this plugin touches — networks, subnetworks, firewalls,
 * routes, routers and peerings all live behind {@code compute.googleapis.com}. IAM still decides what the
 * service account may actually do with each; the token only says who is asking.
 *
 * <h2>The JWT-bearer flow in plain JDK</h2>
 *
 * Build a short-lived JWT asserting the service account and scope, sign it RS256 with
 * {@link java.security.Signature}, and exchange it at the token endpoint. This is what the Google client library
 * does; doing it directly is what lets the plugin ship with no Google SDK dependency.
 *
 * <h2>Tokens are credentials</h2>
 *
 * They live in memory only, keyed by service account, and are reused until shortly before expiry. They are never
 * written to the data store, a log, a node output, or the model's context. Thread-safe.
 */
public final class GoogleTokenSource {

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final long TOKEN_LIFETIME_SECONDS = 3600;
    private static final long EXPIRY_SKEW_SECONDS = 60;

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    private record CachedToken(String value, long expiresAtEpochSecond) {
        boolean isFresh(long now) {
            return now < expiresAtEpochSecond - EXPIRY_SKEW_SECONDS;
        }
    }

    /** @return a valid bearer token, minting one only when the cache is cold or the cached token is near expiry */
    public String accessToken(GoogleCredentials credentials, PluginHttpClient http) {
        long now = System.currentTimeMillis() / 1000;
        CachedToken cached = cache.get(credentials.clientEmail());
        if (cached != null && cached.isFresh(now)) {
            return cached.value();
        }
        CachedToken minted = mint(credentials, http, now);
        cache.put(credentials.clientEmail(), minted);
        return minted.value();
    }

    private CachedToken mint(GoogleCredentials credentials, PluginHttpClient http, long now) {
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
                StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(signedJwt(credentials, now), StandardCharsets.UTF_8);

        HttpResponseView response = http.execute(HttpRequestSpec.post(credentials.tokenUri(), form)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeoutMillis(20_000)
                .build());
        if (!response.isSuccess()) {
            throw new GcpNetworkException("GCP_AUTHENTICATION_FAILED",
                    "Could not obtain a Google access token (the token endpoint returned HTTP "
                            + response.statusCode() + "). Check that the service-account key is current and its "
                            + "key has not been disabled.", response.statusCode() >= 500);
        }
        Map<String, Object> body = Json.parseObject(response.body());
        Object token = body.get("access_token");
        if (token == null) {
            throw new GcpNetworkException("GCP_AUTHENTICATION_FAILED",
                    "The Google token endpoint returned no access token.", false);
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : TOKEN_LIFETIME_SECONDS;
        return new CachedToken(String.valueOf(token), now + expiresIn);
    }

    private String signedJwt(GoogleCredentials credentials, long now) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", credentials.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", credentials.tokenUri());
        claims.put("iat", now);
        claims.put("exp", now + TOKEN_LIFETIME_SECONDS);

        String signingInput = base64Url(Json.write(header)) + "." + base64Url(Json.write(claims));
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(credentials.privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (java.security.GeneralSecurityException ex) {
            // No key material in the message.
            throw new GcpNetworkException("GCP_AUTHENTICATION_FAILED",
                    "Could not sign the Google authentication assertion.", false);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Test aid: drops any cached token for a service account. */
    void evict(String clientEmail) {
        cache.remove(clientEmail);
    }
}
