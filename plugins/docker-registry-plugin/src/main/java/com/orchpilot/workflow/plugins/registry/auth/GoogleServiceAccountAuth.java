package com.orchpilot.workflow.plugins.registry.auth;

import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Mints Google OAuth2 access tokens from a service-account key, using only the JDK.
 *
 * <p>The JWT-bearer flow the Google client libraries perform: build a short-lived JWT asserting the service
 * account and the required scope, sign it RS256 with the account's private key, and exchange it at the token
 * endpoint. Written directly against {@link java.security.Signature} because no Google SDK can be added to this
 * offline build — and, usefully, because it keeps the plugin dependency-free.
 *
 * <p>The key material is never rendered to a string and never leaves this class; only the resulting short-lived
 * access token does.
 */
public final class GoogleServiceAccountAuth {

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final long LIFETIME_SECONDS = 3600;

    private GoogleServiceAccountAuth() {
    }

    /**
     * @param serviceAccountJson the full service-account key file contents, from the secret store
     * @return a bearer access token
     */
    public static String accessToken(String serviceAccountJson, PluginHttpClient http, long timeoutMillis) {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    "Artifact Registry needs a Google service-account JSON key in the connection secret.", false);
        }
        Map<String, Object> key;
        try {
            key = Json.parseObject(serviceAccountJson);
        } catch (RuntimeException ex) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    "The GCP credentials secret is not valid service-account JSON.", false);
        }
        String clientEmail = text(key, "client_email");
        String privateKeyPem = text(key, "private_key");
        String tokenUri = text(key, "token_uri");
        if (clientEmail == null || privateKeyPem == null) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    "The GCP credentials secret is missing client_email or private_key.", false);
        }
        if (tokenUri == null || tokenUri.isBlank()) {
            tokenUri = DEFAULT_TOKEN_URI;
        }

        long now = System.currentTimeMillis() / 1000;
        String assertion = signedJwt(clientEmail, tokenUri, privateKeyPem, now);
        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + enc(assertion);

        HttpResponseView response = http.execute(HttpRequestSpec.builder("POST", tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .body(form)
                .timeoutMillis(timeoutMillis)
                .build());
        if (!response.isSuccess()) {
            throw RegistryException.authentication(
                    "the Google token endpoint returned HTTP " + response.statusCode());
        }
        Object token = Json.parseObject(response.body()).get("access_token");
        if (token == null) {
            throw RegistryException.authentication("Google returned no access token");
        }
        return String.valueOf(token);
    }

    private static String signedJwt(String clientEmail, String tokenUri, String privateKeyPem, long now) {
        String header = base64Url(Json.write(Map.of("alg", "RS256", "typ", "JWT")));
        String claims = base64Url(Json.write(Map.of(
                "iss", clientEmail,
                "scope", SCOPE,
                "aud", tokenUri,
                "iat", now,
                "exp", now + LIFETIME_SECONDS)));
        String signingInput = header + "." + claims;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(parsePrivateKey(privateKeyPem));
            signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (Exception ex) {
            // No key material in the message.
            throw RegistryException.authentication("the Google authentication assertion could not be signed");
        }
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String text(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
