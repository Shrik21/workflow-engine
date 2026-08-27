package com.orchpilot.workflow.sdk.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of an HTTP response returned by {@link PluginHttpClient}.
 *
 * @since 1.0.0
 */
public final class HttpResponseView {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final String body;
    private final long durationMillis;

    /**
     * @param statusCode     HTTP status code
     * @param headers        response headers; {@code null} is treated as empty
     * @param body           response body as text; {@code null} is treated as empty
     * @param durationMillis wall-clock duration of the call
     */
    public HttpResponseView(int statusCode, Map<String, List<String>> headers, String body, long durationMillis) {
        this.statusCode = statusCode;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
        this.durationMillis = durationMillis;
    }

    /** @return HTTP status code */
    public int statusCode() {
        return statusCode;
    }

    /** @return unmodifiable response headers */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** @return response body as text, never {@code null} */
    public String body() {
        return body;
    }

    /** @return wall-clock duration of the call in milliseconds */
    public long durationMillis() {
        return durationMillis;
    }

    /** @return {@code true} for 2xx status codes */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * @param name header name, matched case-insensitively
     * @return the first value for the header, or {@code null}
     */
    public String firstHeader(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "HttpResponseView{status=" + statusCode + ", bodyLength=" + body.length() + "}";
    }
}
