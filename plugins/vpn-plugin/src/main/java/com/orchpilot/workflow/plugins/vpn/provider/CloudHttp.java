package com.orchpilot.workflow.plugins.vpn.provider;

import com.orchpilot.workflow.plugins.vpn.spi.VpnOperationException;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.exception.PluginExecutionException;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;

import java.util.Map;

/**
 * Every cloud call a provider makes, routed through the engine's HTTP client.
 *
 * <h2>Why this and not an SDK</h2>
 *
 * This is the seam that keeps the cloud providers inside the platform's rules. {@link PluginHttpClient}
 * enforces the plugin's host allowlist, caps the timeout and the response size, pools connections and records
 * the call. A provider that reached for {@code software.amazon.awssdk} would open its own sockets outside all
 * of that, which is exactly what the allowlist exists to prevent — and it would make granting the plugin
 * {@code ec2.ap-south-1.amazonaws.com} through the console meaningless. So a cloud host that is not on the
 * allowlist fails here with a message telling the operator to grant it, rather than silently connecting.
 */
public final class CloudHttp {

    private final PluginHttpClient http;

    public CloudHttp(PluginHttpClient http) {
        this.http = http;
    }

    /**
     * Performs a request already built and, where needed, signed by the caller.
     *
     * @param spec     the request
     * @param what     a description for an error message, never carrying a credential
     * @return the response, including non-2xx statuses
     * @throws VpnOperationException when the host is off the allowlist, or the transport fails
     */
    public HttpResponseView send(HttpRequestSpec spec, String what) {
        try {
            return http.execute(spec);
        } catch (PluginSecurityException ex) {
            // The one failure worth a bespoke message: the fix is a specific action in the console.
            throw new VpnOperationException("VPN_CONNECTION_FAILED",
                    "This plugin is not allowed to call the host for " + what + ". Add it to the plugin's "
                            + "allowed hosts (Plugins → the version → Edit), then try again.", false, ex);
        } catch (PluginExecutionException ex) {
            throw new VpnOperationException("VPN_CONNECTION_FAILED",
                    "The call to " + what + " failed: " + ex.getClass().getSimpleName() + ".", true, ex);
        }
    }

    /** A GET with headers. */
    public HttpResponseView get(String uri, Map<String, String> headers, String what) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder("GET", uri);
        headers.forEach(builder::header);
        return send(builder.build(), what);
    }

    /**
     * @param response the response
     * @return whether the status is 2xx
     */
    public static boolean ok(HttpResponseView response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    /**
     * Turns a non-2xx cloud response into a classified failure.
     *
     * @param response the response
     * @param what     what was attempted
     * @return the exception to throw
     */
    public static VpnOperationException failure(HttpResponseView response, String what) {
        int code = response.statusCode();
        if (code == 401 || code == 403) {
            return new VpnOperationException("VPN_AUTHENTICATION_ERROR",
                    "The provider rejected the credentials for " + what + " (HTTP " + code + "). Check the "
                            + "credential and that it is allowed to perform this operation.", false);
        }
        boolean retryable = code == 429 || code >= 500;
        return new VpnOperationException("VPN_PROVIDER_ERROR",
                "The provider returned HTTP " + code + " for " + what + ".", retryable);
    }
}
