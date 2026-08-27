package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The wire: correct URLs and methods for each call, parsed bodies, and status mapped to typed failures. */
class GcpComputeClientTest {

    private final FakeHttpClient http = new FakeHttpClient();
    private final GcpComputeClient client = new GcpComputeClient(http, 30_000);

    @Test
    void insertPostsTheInstanceToTheZoneInstancesUrl() {
        http.on("POST " + GcpComputeClient.BASE_URL, 200, "{\"name\":\"op-1\",\"status\":\"PENDING\"}");

        Map<String, Object> op = client.insertInstance("tok", "proj", "asia-south1-a",
                Map.of("name", "vm-1"));

        assertThat(op.get("name")).isEqualTo("op-1");
        HttpRequestSpec sent = http.lastRequestMatching("/instances");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo(GcpComputeClient.BASE_URL
                + "/projects/proj/zones/asia-south1-a/instances");
        assertThat(sent.headers().get("Authorization")).isEqualTo("Bearer tok");
        assertThat(sent.body()).contains("vm-1");
    }

    @Test
    void getStartAndDeleteHitTheExpectedUrls() {
        http.on("GET " + GcpComputeClient.BASE_URL, 200, "{\"name\":\"vm-1\",\"status\":\"RUNNING\"}")
                .on("POST " + GcpComputeClient.BASE_URL, 200, "{\"name\":\"op-2\"}")
                .on("DELETE " + GcpComputeClient.BASE_URL, 200, "{\"name\":\"op-3\"}");

        assertThat(client.getInstance("t", "proj", "z", "vm-1").get("status")).isEqualTo("RUNNING");
        client.instanceAction("t", "proj", "z", "vm-1", "start");
        client.deleteInstance("t", "proj", "z", "vm-1");

        assertThat(http.lastRequestMatching("/start").uri()).endsWith("/instances/vm-1/start");
        assertThat(http.lastRequestMatching("vm-1").uri()).contains("/instances/vm-1");
    }

    @Test
    void nonSuccessStatusBecomesATypedException() {
        http.on("GET " + GcpComputeClient.BASE_URL, 403,
                "{\"error\":{\"message\":\"compute.instances.get denied\"}}");

        assertThatThrownBy(() -> client.getInstance("t", "proj", "z", "vm-1"))
                .isInstanceOf(GcpApiException.class)
                .satisfies(ex -> assertThat(((GcpApiException) ex).errorCode()).isEqualTo("GCP_PERMISSION_DENIED"));
    }
}
