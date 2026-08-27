package com.orchpilot.workflow.sdk.context;

import com.orchpilot.workflow.sdk.exception.PluginExecutionException;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;

/**
 * Engine-owned HTTP client offered to plugins.
 *
 * <p>Using it is not mandatory, but it is the only path on which the engine can enforce the
 * plugin's declared host allowlist, cap timeouts and response sizes, pool connections and emit
 * metrics. A plugin that opens its own sockets bypasses all of that, which is precisely why
 * untrusted plugins belong in a separate process rather than behind an in-JVM API.
 *
 * @since 1.0.0
 */
public interface PluginHttpClient {

    /**
     * Performs the request synchronously.
     *
     * @param request the request to perform
     * @return the response, including non-2xx statuses; only transport failures throw
     * @throws PluginSecurityException  when the target host is not on the plugin's allowlist
     * @throws PluginExecutionException on timeout, connection failure or an oversized response
     */
    HttpResponseView execute(HttpRequestSpec request);
}
