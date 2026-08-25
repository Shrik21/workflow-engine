package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.exception.PluginExecutionException;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The HTTP client plugins are given, with the engine's limits applied.
 *
 * <p>What it enforces:
 * <ul>
 *   <li><b>Host allowlist</b>, with a leading {@code *.} wildcard. An empty list denies everything, so a
 *       plugin installed without an explicit grant cannot reach the network at all.</li>
 *   <li><b>Scheme</b>: only {@code http} and {@code https}. Notably this blocks {@code file:}, which would
 *       otherwise turn an HTTP client into a file reader.</li>
 *   <li><b>Timeout ceiling</b>, so a plugin cannot pin a worker thread indefinitely by asking for a
 *       one-hour timeout.</li>
 *   <li><b>Response size ceiling</b>, enforced while streaming rather than after buffering. A ten gigabyte
 *       response must not be read into the heap before being rejected.</li>
 *   <li><b>No automatic redirects.</b> Following a redirect would let an allowlisted host bounce the plugin
 *       to one that is not on the list, which defeats the allowlist entirely.</li>
 * </ul>
 *
 * <p>These are cooperative controls. A plugin that opens its own {@code Socket} bypasses every one of them.
 * That is the fundamental limit of in-process plugins and the reason untrusted code belongs in a separate
 * container.
 */
public class RestrictedHttpClient implements PluginHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RestrictedHttpClient.class);

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Headers a plugin may not set, because the engine or the JDK owns them. */
    private static final Set<String> BLOCKED_HEADERS = Set.of("host", "content-length", "connection",
            "upgrade", "transfer-encoding", "expect");

    private final HttpClient delegate;
    private final String coordinate;
    private final List<String> allowedHosts;
    private final long maxTimeoutMillis;
    private final long maxResponseBytes;

    /**
     * @param delegate         shared JDK client, owned by the engine
     * @param coordinate       {@code pluginId:version}, for diagnostics
     * @param allowedHosts     hosts this plugin may call; empty denies all
     * @param maxTimeoutMillis ceiling on per-request timeouts
     * @param maxResponseBytes ceiling on buffered response size
     */
    public RestrictedHttpClient(HttpClient delegate, String coordinate, List<String> allowedHosts,
                                long maxTimeoutMillis, long maxResponseBytes) {
        this.delegate = delegate;
        this.coordinate = coordinate;
        this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        this.maxTimeoutMillis = maxTimeoutMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public HttpResponseView execute(HttpRequestSpec request) {
        if (request == null) {
            throw new PluginExecutionException("HTTP_REQUEST_INVALID", "No request was supplied");
        }
        URI uri = parse(request.uri());
        requireAllowed(uri);

        Duration timeout = effectiveTimeout(request.timeout());
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        applyMethod(builder, request);
        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            String name = header.getKey();
            if (name == null || header.getValue() == null) {
                continue;
            }
            if (BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                log.debug("Plugin {} attempted to set restricted header '{}'; ignored", coordinate, name);
                continue;
            }
            builder.header(name, header.getValue());
        }

        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> response = delegate.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            String body = readBounded(response.body());
            long durationMillis = (System.nanoTime() - start) / 1_000_000L;
            log.debug("Plugin {} called {} {} -> {} in {} ms", coordinate, request.method(), uri.getHost(),
                    response.statusCode(), durationMillis);
            return new HttpResponseView(response.statusCode(), response.headers().map(), body,
                    durationMillis);
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new PluginExecutionException("HTTP_TIMEOUT",
                    "Request to " + uri.getHost() + " timed out after " + timeout.toMillis() + " ms", true, ex);
        } catch (IOException ex) {
            throw new PluginExecutionException("HTTP_TRANSPORT_ERROR",
                    "Request to " + uri.getHost() + " failed: " + ex.getMessage(), true, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PluginExecutionException("HTTP_INTERRUPTED",
                    "Request to " + uri.getHost() + " was interrupted", true, ex);
        }
    }

    private void applyMethod(HttpRequest.Builder builder, HttpRequestSpec request) {
        String method = request.method();
        String body = request.body();
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> {
                // The JDK's DELETE() takes no body; route body-carrying deletes through method().
                if (body == null) {
                    builder.DELETE();
                } else {
                    builder.method("DELETE", publisher);
                }
            }
            case "POST" -> builder.POST(publisher);
            case "PUT" -> builder.PUT(publisher);
            default -> builder.method(method, publisher);
        }
    }

    private URI parse(String uri) {
        try {
            URI parsed = new URI(uri);
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                throw new PluginExecutionException("HTTP_REQUEST_INVALID",
                        "URL must be absolute and include a host: " + uri);
            }
            return parsed;
        } catch (URISyntaxException ex) {
            throw new PluginExecutionException("HTTP_REQUEST_INVALID", "Malformed URL: " + uri, false, ex);
        }
    }

    private void requireAllowed(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new PluginSecurityException("Scheme '" + scheme + "' is not permitted; use http or https");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (allowedHosts.isEmpty()) {
            throw new PluginSecurityException("Plugin '" + coordinate + "' has no allowed hosts, so it "
                    + "cannot call " + host + ". Grant it at upload time with allowedHosts.");
        }
        for (String allowed : allowedHosts) {
            if (matches(host, allowed)) {
                return;
            }
        }
        throw new PluginSecurityException("Plugin '" + coordinate + "' may not call host '" + host
                + "'. Allowed: " + allowedHosts);
    }

    /**
     * @param host    the host being called
     * @param pattern an exact host, {@code *} for any, or {@code *.example.com} for a subdomain match
     * @return whether the host is permitted by the pattern
     */
    static boolean matches(String host, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String candidate = pattern.trim().toLowerCase(Locale.ROOT);
        if ("*".equals(candidate)) {
            return true;
        }
        if (candidate.startsWith("*.")) {
            String suffix = candidate.substring(1); // keeps the leading dot
            // "*.example.com" matches "api.example.com" and "example.com" itself.
            return host.endsWith(suffix) || host.equals(candidate.substring(2));
        }
        return host.equals(candidate);
    }

    private Duration effectiveTimeout(Duration requested) {
        long ceiling = maxTimeoutMillis > 0 ? maxTimeoutMillis : 60_000;
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return Duration.ofMillis(ceiling);
        }
        return Duration.ofMillis(Math.min(requested.toMillis(), ceiling));
    }

    /**
     * Reads at most {@link #maxResponseBytes} and rejects anything larger, without buffering the excess.
     */
    private String readBounded(InputStream in) throws IOException {
        long limit = maxResponseBytes > 0 ? maxResponseBytes : Long.MAX_VALUE;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(8_192);
        byte[] chunk = new byte[8_192];
        long total = 0;
        try (InputStream stream = in) {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                total += read;
                if (total > limit) {
                    throw new PluginExecutionException("HTTP_RESPONSE_TOO_LARGE",
                            "Response exceeded the " + limit + " byte limit for plugin " + coordinate);
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
