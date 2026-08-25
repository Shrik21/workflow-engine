package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP status is mapped to a stable OrchPilot error code, only transient failures are retryable, and Google's own
 * message (which usually names the missing IAM permission) is surfaced without anything sensitive attached.
 */
class GcpApiExceptionTest {

    private static HttpResponseView response(int status, String body) {
        return new HttpResponseView(status, Map.of(), body, 1);
    }

    @Test
    void permissionDeniedIsNotRetryableAndKeepsGoogleMessage() {
        String body = "{\"error\":{\"code\":403,\"message\":\"Required 'compute.instances.create' permission\"}}";
        GcpApiException ex = GcpApiException.of(response(403, body));
        assertThat(ex.errorCode()).isEqualTo("GCP_PERMISSION_DENIED");
        assertThat(ex.retryable()).isFalse();
        assertThat(ex.getMessage()).contains("compute.instances.create");
    }

    @Test
    void notFoundAndQuotaAndServerErrorsMapAsSpecified() {
        assertThat(GcpApiException.of(response(404, "{}")).errorCode()).isEqualTo("GCP_INSTANCE_NOT_FOUND");
        GcpApiException quota = GcpApiException.of(response(429, "{}"));
        assertThat(quota.errorCode()).isEqualTo("GCP_QUOTA_EXCEEDED");
        assertThat(quota.retryable()).isTrue();
        GcpApiException server = GcpApiException.of(response(503, "{}"));
        assertThat(server.errorCode()).isEqualTo("GCP_API_UNAVAILABLE");
        assertThat(server.retryable()).isTrue();
    }

    @Test
    void extractMessageToleratesNonJson() {
        assertThat(GcpApiException.extractMessage("not json at all")).isEqualTo("not json at all");
        assertThat(GcpApiException.extractMessage("")).isNull();
    }
}
