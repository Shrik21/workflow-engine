package com.orchpilot.workflow.sdk.context;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of an outbound HTTP call, executed by the engine on the plugin's behalf.
 *
 * <p>Plugins describe requests rather than performing them so that the engine can enforce host
 * allowlists, timeout and response-size ceilings, connection pooling and metrics uniformly. See
 * {@link PluginHttpClient}.
 *
 * @since 1.0.0
 */
public final class HttpRequestSpec {

    private final String method;
    private final String uri;
    private final Map<String, String> headers;
    private final String body;
    private final Duration timeout;

    private HttpRequestSpec(Builder builder) {
        this.method = builder.method == null ? "GET" : builder.method.toUpperCase(java.util.Locale.ROOT);
        this.uri = Objects.requireNonNull(builder.uri, "uri");
        Map<String, String> copy = new LinkedHashMap<>(builder.headers);
        this.headers = Collections.unmodifiableMap(copy);
        this.body = builder.body;
        this.timeout = builder.timeout;
    }

    /**
     * @param method HTTP method
     * @param uri    absolute request URI
     * @return a new builder
     */
    public static Builder builder(String method, String uri) {
        return new Builder(method, uri);
    }

    /**
     * @param uri absolute request URI
     * @return a builder for a GET request
     */
    public static Builder get(String uri) {
        return new Builder("GET", uri);
    }

    /**
     * @param uri  absolute request URI
     * @param body request body
     * @return a builder for a POST request
     */
    public static Builder post(String uri, String body) {
        return new Builder("POST", uri).body(body);
    }

    /** @return upper-case HTTP method */
    public String method() {
        return method;
    }

    /** @return absolute request URI */
    public String uri() {
        return uri;
    }

    /** @return unmodifiable request headers */
    public Map<String, String> headers() {
        return headers;
    }

    /** @return request body, or {@code null} */
    public String body() {
        return body;
    }

    /** @return per-request timeout, or {@code null} to use the engine default */
    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return "HttpRequestSpec{" + method + " " + uri + "}";
    }

    /**
     * Mutable builder for {@link HttpRequestSpec}.
     *
     * @since 1.0.0
     */
    public static final class Builder {

        private final String method;
        private final String uri;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;
        private Duration timeout;

        private Builder(String method, String uri) {
            this.method = method;
            this.uri = uri;
        }

        /**
         * @param name  header name
         * @param value header value; {@code null} removes the header
         * @return this builder
         */
        public Builder header(String name, String value) {
            if (name != null) {
                if (value == null) {
                    headers.remove(name);
                } else {
                    headers.put(name, value);
                }
            }
            return this;
        }

        /**
         * @param values headers to add; {@code null} is ignored
         * @return this builder
         */
        public Builder headers(Map<String, String> values) {
            if (values != null) {
                values.forEach(this::header);
            }
            return this;
        }

        /**
         * @param value request body
         * @return this builder
         */
        public Builder body(String value) {
            this.body = value;
            return this;
        }

        /**
         * @param value per-request timeout; the engine caps it at its configured maximum
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /**
         * @param millis per-request timeout in milliseconds; ignored when not positive
         * @return this builder
         */
        public Builder timeoutMillis(long millis) {
            this.timeout = millis > 0 ? Duration.ofMillis(millis) : null;
            return this;
        }

        /** @return an immutable request specification */
        public HttpRequestSpec build() {
            return new HttpRequestSpec(this);
        }
    }
}
