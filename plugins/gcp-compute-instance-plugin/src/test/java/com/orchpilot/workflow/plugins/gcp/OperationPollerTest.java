package com.orchpilot.workflow.plugins.gcp;

import com.orchpilot.workflow.sdk.context.HttpResponseView;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The async guarantee the spec insists on: a create is not "done" on acceptance. The poller drives the operation to
 * DONE, raises an operation-level error, and stops on timeout or cancellation — all quickly, with a 1ms interval.
 */
class OperationPollerTest {

    private static final String OPS = "/operations/op-1";

    private GcpComputeClient clientReturning(HttpResponseView... sequence) {
        AtomicInteger i = new AtomicInteger();
        FakeHttpClient http = new FakeHttpClient().on(
                req -> req.uri().contains(OPS),
                req -> sequence[Math.min(i.getAndIncrement(), sequence.length - 1)]);
        return new GcpComputeClient(http, 5_000);
    }

    private static HttpResponseView body(String json) {
        return new HttpResponseView(200, Map.of(), json, 1);
    }

    @Test
    void pollsUntilDone() {
        GcpComputeClient client = clientReturning(
                body("{\"name\":\"op-1\",\"status\":\"RUNNING\"}"),
                body("{\"name\":\"op-1\",\"status\":\"DONE\"}"));

        Map<String, Object> done = OperationPoller.await(client, () -> "t", "proj", "z", "op-1",
                5_000, 1, () -> false);

        assertThat(done.get("status")).isEqualTo("DONE");
    }

    @Test
    void surfacesAnOperationError() {
        GcpComputeClient client = clientReturning(body(
                "{\"name\":\"op-1\",\"status\":\"DONE\",\"error\":{\"errors\":[{\"code\":\"QUOTA_EXCEEDED\","
                        + "\"message\":\"quota exceeded\"}]}}"));

        assertThatThrownBy(() -> OperationPoller.await(client, () -> "t", "proj", "z", "op-1", 5_000, 1,
                () -> false))
                .isInstanceOf(GcpApiException.class)
                .satisfies(ex -> assertThat(((GcpApiException) ex).errorCode()).isEqualTo("GCP_OPERATION_FAILED"))
                .hasMessageContaining("quota exceeded");
    }

    @Test
    void stopsWhenCancelled() {
        GcpComputeClient client = clientReturning(body("{\"name\":\"op-1\",\"status\":\"RUNNING\"}"));

        assertThatThrownBy(() -> OperationPoller.await(client, () -> "t", "proj", "z", "op-1", 5_000, 1,
                () -> true))
                .isInstanceOf(GcpApiException.class)
                .satisfies(ex -> assertThat(((GcpApiException) ex).errorCode())
                        .isEqualTo("GCP_OPERATION_CANCELLED"));
    }

    @Test
    void timesOutWhenNeverDone() {
        GcpComputeClient client = clientReturning(body("{\"name\":\"op-1\",\"status\":\"RUNNING\"}"));

        assertThatThrownBy(() -> OperationPoller.await(client, () -> "t", "proj", "z", "op-1", 5, 1,
                () -> false))
                .isInstanceOf(GcpApiException.class)
                .satisfies(ex -> assertThat(((GcpApiException) ex).errorCode()).isEqualTo("GCP_OPERATION_TIMEOUT"));
    }
}
