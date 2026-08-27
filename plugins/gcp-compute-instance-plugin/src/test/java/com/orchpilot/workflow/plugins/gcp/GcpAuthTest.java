package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.json.Json;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JWT-bearer flow, with no Google SDK: a correctly-signed assertion is built and exchanged for a token, and the
 * token is cached rather than re-minted on every call.
 */
class GcpAuthTest {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";

    @Test
    void mintsAnAccessTokenWithACorrectlyShapedAssertion() {
        GcpCredentials creds = GcpCredentials.fromServiceAccountJson(
                TestServiceAccount.json("vm@test.iam.gserviceaccount.com", TOKEN_URI));
        FakeHttpClient http = new FakeHttpClient()
                .on("POST " + TOKEN_URI, 200, "{\"access_token\":\"ya29.token\",\"expires_in\":3600}");

        String token = new GcpAuth().accessToken(creds, http);

        assertThat(token).isEqualTo("ya29.token");

        // Decode the assertion that was sent and check its header and claims.
        String form = http.lastRequestMatching(TOKEN_URI).body();
        String assertion = form.split("assertion=")[1];
        assertion = URLDecoder.decode(assertion, StandardCharsets.UTF_8);
        String[] parts = assertion.split("\\.");
        assertThat(parts).hasSize(3);
        Map<String, Object> header = Json.parseObject(decode(parts[0]));
        Map<String, Object> claims = Json.parseObject(decode(parts[1]));
        assertThat(header.get("alg")).isEqualTo("RS256");
        assertThat(claims.get("iss")).isEqualTo("vm@test.iam.gserviceaccount.com");
        assertThat(claims.get("aud")).isEqualTo(TOKEN_URI);
        assertThat(String.valueOf(claims.get("scope"))).contains("compute");
    }

    @Test
    void cachesTheTokenBetweenCalls() {
        GcpCredentials creds = GcpCredentials.fromServiceAccountJson(
                TestServiceAccount.json("vm@test.iam.gserviceaccount.com", TOKEN_URI));
        FakeHttpClient http = new FakeHttpClient()
                .on("POST " + TOKEN_URI, 200, "{\"access_token\":\"ya29.token\",\"expires_in\":3600}");
        GcpAuth auth = new GcpAuth();

        auth.accessToken(creds, http);
        auth.accessToken(creds, http);

        // Only one token exchange despite two calls: the second was served from cache.
        assertThat(http.countMatching(TOKEN_URI)).isEqualTo(1);
    }

    @Test
    void reportsAuthFailureWhenTheTokenEndpointRejectsTheKey() {
        GcpCredentials creds = GcpCredentials.fromServiceAccountJson(
                TestServiceAccount.json("vm@test.iam.gserviceaccount.com", TOKEN_URI));
        FakeHttpClient http = new FakeHttpClient().on("POST " + TOKEN_URI, 401, "{\"error\":\"invalid_grant\"}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GcpAuth().accessToken(creds, http))
                .isInstanceOf(GcpApiException.class)
                .satisfies(ex -> assertThat(((GcpApiException) ex).errorCode())
                        .isEqualTo("GCP_AUTHENTICATION_FAILED"));
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
