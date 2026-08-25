package com.orchpilot.workflow.ai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scrubbing of credential-shaped text on its way out of the engine.
 *
 * <p>Each test names a real thing that turns up in an error message. A miss here means a credential is sent to
 * an external tool, so the assertions check the secret is <em>gone</em>, not merely that something changed.
 */
class SensitiveTextScrubberTest {

    private final SensitiveTextScrubber scrubber = new SensitiveTextScrubber();

    @Test
    @DisplayName("removes a PEM private key")
    void removesPrivateKey() {
        String text = """
                Failed to authenticate with key:
                -----BEGIN PRIVATE KEY-----
                MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKj
                MzEfYyjiWA4R4/M2bS1GB4t7NXp98C3SC6dVMvDuictGeurT8jNbvJZHtCSuYEvu
                -----END PRIVATE KEY-----
                while creating the VPC.""";

        String scrubbed = scrubber.scrub(text);

        assertThat(scrubbed).doesNotContain("MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw");
        assertThat(scrubbed).doesNotContain("BEGIN PRIVATE KEY");
        assertThat(scrubbed).contains(SensitiveTextScrubber.MASK);
        // The surrounding message is what makes the error understandable, so it must survive.
        assertThat(scrubbed).contains("while creating the VPC");
    }

    @Test
    @DisplayName("removes a Google access token")
    void removesGoogleToken() {
        String scrubbed = scrubber.scrub("Request failed with token ya29.a0AfH6SMBx7Qw9_ZZ-1234567890abcdef");

        assertThat(scrubbed).doesNotContain("ya29.a0AfH6SMBx7Qw9");
        assertThat(scrubbed).contains(SensitiveTextScrubber.MASK);
    }

    @Test
    @DisplayName("removes an Authorization header")
    void removesAuthorizationHeader() {
        String scrubbed = scrubber.scrub("HTTP 403. Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456");

        assertThat(scrubbed).doesNotContain("abcdefghijklmnopqrstuvwxyz123456");
        assertThat(scrubbed).contains("HTTP 403");
    }

    @Test
    @DisplayName("removes a JWT")
    void removesJwt() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

        String scrubbed = scrubber.scrub("Token rejected: " + jwt);

        assertThat(scrubbed).doesNotContain("dozjgNryP4J3jVmNHl0w5N");
        assertThat(scrubbed).contains("Token rejected");
    }

    @Test
    @DisplayName("removes vendor API keys")
    void removesVendorKeys() {
        assertThat(scrubber.scrub("key sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx"))
                .doesNotContain("AbCdEfGhIjKlMnOpQrStUvWx");
        assertThat(scrubber.scrub("key AIzaSyD-1234567890abcdefghijklmnopqrstu"))
                .doesNotContain("AIzaSyD-1234567890abcdefghijklmnopqrstu");
        assertThat(scrubber.scrub("key AKIAIOSFODNN7EXAMPLE")).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(scrubber.scrub("token ghp_16C7e42F292c6912E7710c838347Ae178B4a"))
                .doesNotContain("16C7e42F292c6912E7710c838347Ae178B4a");
    }

    @Test
    @DisplayName("keeps the field name but removes its value")
    void masksNamedFieldValues() {
        String scrubbed = scrubber.scrub("{\"private_key\": \"super-secret-value\", \"project\": \"acme\"}");

        assertThat(scrubbed).doesNotContain("super-secret-value");
        // The name is kept on purpose: "a private key was involved" is useful for the analysis, the value is not.
        assertThat(scrubbed).contains("private_key");
        assertThat(scrubbed).contains("acme");
    }

    @Test
    @DisplayName("removes inline credentials from a connection string")
    void removesUriCredentials() {
        String scrubbed = scrubber.scrub("Cannot reach mongodb://admin:hunter2@db.internal:27017/orchpilot");

        assertThat(scrubbed).doesNotContain("hunter2");
        assertThat(scrubbed).doesNotContain("admin:hunter2");
        // The host is the diagnostically useful part and is kept.
        assertThat(scrubbed).contains("db.internal:27017");
    }

    @Test
    @DisplayName("leaves an ordinary permission error untouched")
    void leavesOrdinaryTextAlone() {
        String message = "Permission denied for creating VPC 'prod-vpc'. The service account needs the "
                + "matching Compute IAM role. Required: compute.networks.create for "
                + "projects/project-b2826f14/global/networks/prod-vpc";

        assertThat(scrubber.scrub(message)).isEqualTo(message);
        assertThat(scrubber.containsSensitive(message)).isFalse();
    }

    @Test
    @DisplayName("reports whether anything was removed, so a leak upstream can be found")
    void reportsWhetherSensitive() {
        assertThat(scrubber.containsSensitive("plain error text")).isFalse();
        assertThat(scrubber.containsSensitive("token ya29.abcdefghijklmnop")).isTrue();
    }

    @Test
    @DisplayName("tolerates null and empty")
    void tolerantOfNothing() {
        assertThat(scrubber.scrub(null)).isNull();
        assertThat(scrubber.scrub("")).isEmpty();
        assertThat(scrubber.containsSensitive(null)).isFalse();
    }
}
