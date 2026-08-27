package com.orchpilot.workflow.pluginserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The registry client, over HTTP.
 *
 * <h2>Token handling</h2>
 *
 * <p>One token is held and reused until shortly before it expires, then exchanged for another. The alternative,
 * a token per request, triples the traffic and makes the registry's BCrypt verification the cost of every sync.
 * The margin matters: a token that expires in flight produces a 401 on a request that was valid when it was sent,
 * so it is replaced while it still has a minute left, and a 401 anyway is retried exactly once with a fresh token.
 *
 * <h2>Why RestClient</h2>
 *
 * <p>Synchronous and blocking, which is what every caller here wants: a sync on a scheduler thread and an install
 * a user is waiting for. A reactive client would add a programming model to the engine for no benefit at either
 * call site.
 */
@Component
public class RestPluginServerClient implements PluginServerClient {

    private static final Logger log = LoggerFactory.getLogger(RestPluginServerClient.class);

    /** Replace a token this long before it expires, so one cannot lapse mid-request. */
    private static final Duration RENEWAL_MARGIN = Duration.ofMinutes(1);

    private final PluginServerProperties properties;
    private final RestClient rest;
    private final RestClient downloadRest;

    /** The current token and when it stops being usable. Replaced wholesale, never mutated. */
    private final AtomicReference<Token> token = new AtomicReference<>();

    private record Token(String value, Instant usableUntil) {

        boolean isUsable() {
            return Instant.now().isBefore(usableUntil);
        }
    }

    /**
     * Builds its own clients rather than taking an injected {@code RestClient.Builder}.
     *
     * <p>Spring Boot 4 splits the builder's auto-configuration into a module the web starter does not pull in, so
     * injecting it fails the context at startup. It would be the wrong dependency regardless: everything about
     * these clients, the base URL and both timeouts, is configured here, so an injected builder would contribute
     * only defaults that are then overwritten.
     */
    public RestPluginServerClient(PluginServerProperties properties) {
        this.properties = properties;
        // A URL that cannot resolve, when nothing is configured. Every method checks isConfigured() first, so
        // this is never called; a base URL of null would fail at construction instead of at use.
        String baseUrl = properties.getBaseUrl().isBlank()
                ? "http://plugin-server.invalid" : properties.getBaseUrl();

        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory(properties.getRequestTimeout()))
                .build();
        // A separate client for archives: a five-minute read timeout is right for a large download and wrong
        // for a catalogue fetch, where it would turn an unreachable registry into a five-minute stall.
        this.downloadRest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory(properties.getDownloadTimeout()))
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory factory(Duration timeout) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeout);
        return factory;
    }

    @Override
    public CatalogResult fetchCatalog(String knownEtag) {
        if (!properties.isConfigured()) {
            return CatalogResult.failed("No plugin registry is configured.");
        }
        try {
            return withToken(bearer -> {
                var request = rest.get().uri("/api/plugin-catalog")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .accept(MediaType.APPLICATION_JSON);
                if (knownEtag != null && !knownEtag.isBlank()) {
                    request = request.header(HttpHeaders.IF_NONE_MATCH, knownEtag);
                }
                return request.exchange((clientRequest, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == HttpStatus.NOT_MODIFIED.value()) {
                        // Nothing transferred, and the cached catalogue is current. The common case.
                        return CatalogResult.unchanged(etagOf(response.getHeaders(), knownEtag));
                    }
                    if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
                        throw new UnauthorizedException();
                    }
                    if (!status.is2xxSuccessful()) {
                        return CatalogResult.failed("The registry answered " + status.value()
                                + " to a catalogue request.");
                    }
                    List<CatalogRecords.CatalogEntry> entries = response.bodyTo(
                            new ParameterizedTypeReference<List<CatalogRecords.CatalogEntry>>() {
                            });
                    return CatalogResult.fetched(entries == null ? List.of() : entries,
                            etagOf(response.getHeaders(), null));
                });
            });
        } catch (UnauthorizedException ex) {
            return CatalogResult.failed("The registry rejected this engine's credentials. Check "
                    + "plugin.server.client-id and plugin.server.client-secret.");
        } catch (RestClientException | PluginServerUnavailableException ex) {
            // Expected during an outage. Logged at info, not error: the caller falls back to its cache and the
            // platform keeps working, so this is not something to page anybody about.
            log.info("Could not reach the plugin registry at {}: {}", properties.getBaseUrl(),
                    ex.getMessage());
            return CatalogResult.failed(ex.getMessage());
        }
    }

    @Override
    public Optional<CatalogRecords.CatalogEntry> fetchPlugin(String pluginId) {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }
        try {
            return withToken(bearer -> rest.get()
                    .uri("/api/plugins/{id}", pluginId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                            throw new UnauthorizedException();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Optional.<CatalogRecords.CatalogEntry>empty();
                        }
                        return Optional.ofNullable(
                                response.bodyTo(CatalogRecords.CatalogEntry.class));
                    }));
        } catch (UnauthorizedException | RestClientException | PluginServerUnavailableException ex) {
            log.info("Could not read plugin '{}' from the registry: {}", pluginId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public InputStream download(String pluginId, String version) {
        if (!properties.isConfigured()) {
            throw PluginServerUnavailableException.notConfigured();
        }
        try {
            return withToken(bearer -> downloadRest.get()
                    .uri("/api/plugins/{id}/versions/{version}/download", pluginId, version)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
                            throw new UnauthorizedException();
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new PluginServerUnavailableException("The registry answered "
                                    + status.value() + " when asked for " + pluginId + ":" + version
                                    + ". A revoked version is refused deliberately.");
                        }
                        /*
                         * The body is read fully here rather than streamed to the caller.
                         *
                         * exchange() closes the response when it returns, so handing back a lazy stream would
                         * hand back a closed one. Buffering an archive is acceptable where streaming it is not
                         * possible: the size is bounded by the registry's own upload limit, and the alternative
                         * is a leaked connection per install.
                         */
                        try (InputStream body = response.getBody()) {
                            return new java.io.ByteArrayInputStream(body.readAllBytes());
                        } catch (IOException ex) {
                            throw new PluginServerUnavailableException("The download of " + pluginId + ":"
                                    + version + " was interrupted: " + ex.getMessage(), ex);
                        }
                    }, false));
        } catch (UnauthorizedException ex) {
            throw new PluginServerUnavailableException("The registry rejected this engine's credentials.");
        } catch (RestClientException ex) {
            throw new PluginServerUnavailableException("Could not download " + pluginId + ":" + version
                    + " from the registry: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean isReachable() {
        if (!properties.isConfigured()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(rest.get().uri("/actuator/health")
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful(), false));
        } catch (RestClientException ex) {
            return false;
        }
    }

    @Override
    public String describe() {
        return properties.describe();
    }

    // ------------------------------------------------------------------ internals

    /** Marker for a 401, so the retry-once logic is not tangled with transport failures. */
    private static final class UnauthorizedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Runs a call with a valid token, retrying once with a fresh one after a 401.
     *
     * <p>Exactly once. A loop here would turn a permanently wrong credential into an infinite retry against a
     * service that is answering correctly.
     */
    private <T> T withToken(java.util.function.Function<String, T> call) {
        try {
            return call.apply(currentToken());
        } catch (UnauthorizedException ex) {
            log.info("The registry refused this engine's token; requesting another");
            token.set(null);
            return call.apply(currentToken());
        }
    }

    private String currentToken() {
        Token current = token.get();
        if (current != null && current.isUsable()) {
            return current.value();
        }
        Token fresh = requestToken();
        token.set(fresh);
        return fresh.value();
    }

    /**
     * Exchanges the client credentials for a token.
     *
     * <p>Sent as form fields rather than HTTP Basic, because a secret containing a colon is legal and a client
     * that splits Basic credentials on the wrong colon fails in a way that looks like a wrong password.
     */
    private Token requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        try {
            Map<String, Object> body = rest.post().uri("/api/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new PluginServerUnavailableException(
                                    "The registry answered " + response.getStatusCode().value()
                                            + " to a token request. Check the client id and secret.");
                        }
                        return response.bodyTo(new ParameterizedTypeReference<Map<String, Object>>() {
                        });
                    });

            if (body == null || body.get("access_token") == null) {
                throw new PluginServerUnavailableException("The registry returned no access token.");
            }
            long expiresIn = body.get("expires_in") instanceof Number number
                    ? number.longValue()
                    : Duration.ofMinutes(5).toSeconds();
            Instant usableUntil = Instant.now()
                    .plusSeconds(Math.max(1, expiresIn - RENEWAL_MARGIN.toSeconds()));

            log.debug("Obtained a registry token valid for {}s", expiresIn);
            return new Token(String.valueOf(body.get("access_token")), usableUntil);
        } catch (RestClientException ex) {
            throw new PluginServerUnavailableException("Could not obtain a token from the plugin registry at "
                    + properties.getBaseUrl() + ": " + ex.getMessage(), ex);
        }
    }

    private static String etagOf(HttpHeaders headers, String fallback) {
        String etag = headers.getETag();
        return etag == null || etag.isBlank() ? fallback : etag;
    }
}
