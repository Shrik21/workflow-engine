package com.orchpilot.workflow.plugins.gcp;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Waits for a Compute Engine zonal operation to finish, honouring a timeout and cooperative cancellation.
 *
 * <h2>Why a create is not "done" when the API returns</h2>
 *
 * Compute's insert/start/stop/delete calls return immediately with a long-running <em>operation</em>; the VM is
 * still provisioning. The specification is explicit that the plugin must not report success on acceptance alone, so
 * when the node is configured to wait this poller drives the operation to {@code DONE}, surfaces any operation-level
 * error (a quota or permission failure that only appears here, not on the initial call), and stops promptly on
 * timeout or when the workflow is cancelled. The token is fetched through a supplier so a poll that outlives the
 * access token transparently picks up a refreshed one.
 */
final class OperationPoller {

    private OperationPoller() {
    }

    /**
     * @return the final, DONE operation resource
     * @throws GcpApiException on an operation-level error, on timeout, or when cancelled
     */
    static Map<String, Object> await(GcpComputeClient client, Supplier<String> token, String project, String zone,
                                     String operationName, long timeoutMillis, long pollIntervalMillis,
                                     BooleanSupplier cancelled) {
        long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMillis);
        long interval = Math.max(1_000, pollIntervalMillis);

        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new GcpApiException("GCP_OPERATION_CANCELLED",
                        "The workflow was cancelled while waiting for operation " + operationName + ".", false);
            }
            Map<String, Object> operation = client.getZoneOperation(token.get(), project, zone, operationName);
            if ("DONE".equals(operation.get("status"))) {
                failIfErrored(operation);
                return operation;
            }
            if (System.currentTimeMillis() + interval > deadline) {
                throw new GcpApiException("GCP_OPERATION_TIMEOUT",
                        "Timed out waiting for operation " + operationName + " to complete.", false);
            }
            sleep(interval);
        }
    }

    @SuppressWarnings("unchecked")
    private static void failIfErrored(Map<String, Object> operation) {
        Object error = operation.get("error");
        if (error instanceof Map<?, ?> errorMap && errorMap.get("errors") instanceof List<?> errors
                && !errors.isEmpty() && errors.get(0) instanceof Map<?, ?> first) {
            String message = String.valueOf(((Map<String, Object>) first).getOrDefault("message",
                    "the operation reported an error"));
            String code = String.valueOf(((Map<String, Object>) first).getOrDefault("code", "GCP_OPERATION_FAILED"));
            throw new GcpApiException("GCP_OPERATION_FAILED",
                    "The Compute Engine operation failed (" + code + "): " + message, false);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GcpApiException("GCP_OPERATION_CANCELLED",
                    "Interrupted while waiting for a Compute Engine operation.", false);
        }
    }
}
