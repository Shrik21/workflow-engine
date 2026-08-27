package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parsing a real service-account key, and rejecting a malformed one with a message that leaks nothing. */
class GcpCredentialsTest {

    @Test
    void parsesAValidServiceAccountKey() {
        String sa = TestServiceAccount.json("vm@test-project.iam.gserviceaccount.com",
                "https://oauth2.googleapis.com/token");

        GcpCredentials credentials = GcpCredentials.fromServiceAccountJson(sa);

        assertThat(credentials.clientEmail()).isEqualTo("vm@test-project.iam.gserviceaccount.com");
        assertThat(credentials.tokenUri()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(credentials.projectId()).isEqualTo("test-project");
        assertThat(credentials.privateKey()).isNotNull();
        // The key material must never render into a string.
        assertThat(credentials.toString()).doesNotContain("PRIVATE").doesNotContain("test-project");
    }

    @Test
    void rejectsNonJsonAndMissingFields() {
        assertThatThrownBy(() -> GcpCredentials.fromServiceAccountJson("not json"))
                .isInstanceOf(PluginConfigurationException.class);
        assertThatThrownBy(() -> GcpCredentials.fromServiceAccountJson("{\"type\":\"service_account\"}"))
                .isInstanceOf(PluginConfigurationException.class)
                .hasMessageContaining("client_email");
        assertThatThrownBy(() -> GcpCredentials.fromServiceAccountJson(""))
                .isInstanceOf(PluginConfigurationException.class);
    }
}
