package com.orchpilot.workflow.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchpilot.workflow.ai.AIException;
import com.orchpilot.workflow.ai.AIModelProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared HTTP plumbing for the real providers — OpenAI, Anthropic, Ollama and the OpenAI-compatible endpoints.
 *
 * <p>Each provider differs only in its URL, its auth header and the shape of its request and response JSON, so
 * everything else — building a timed HTTP client, posting JSON, mapping transport failures onto the router's
 * retryable/permanent distinction — lives here once. Failures are translated deliberately: a 401 is permanent
 * (bad credentials, do not retry), a timeout or a 5xx or 429 is retryable (the router may retry or fall back),
 * and a malformed body is a permanent bad-response.
 */
abstract class AbstractHttpProvider implements AIModelProvider {

    protected final ObjectMapper mapper = new ObjectMapper();

    /** Builds a client with connect/read timeouts, so a hung provider fails fast rather than pinning a thread. */
    protected RestClient client(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(15, Math.max(1, timeoutSeconds))));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Posts a JSON body and parses the JSON response into a map, translating transport failures to
     * {@link AIException}.
     */
    protected Map<String, Object> postJson(RestClient client, String url, Map<String, String> headers,
                                           Object body) {
        try {
            String response = client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized
                | org.springframework.web.client.HttpClientErrorException.Forbidden ex) {
            throw AIException.unauthorized("The AI provider rejected the credentials.");
        } catch (org.springframework.web.client.HttpClientErrorException ex) {
            boolean rateLimited = ex.getStatusCode().value() == 429;
            throw new AIException(rateLimited ? "PROVIDER_RATE_LIMITED" : "PROVIDER_BAD_REQUEST",
                    "The AI provider returned " + ex.getStatusCode() + providerDetail(ex), rateLimited);
        } catch (org.springframework.web.client.HttpServerErrorException ex) {
            throw AIException.unavailable("The AI provider returned " + ex.getStatusCode()
                    + providerDetail(ex), ex);
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            throw AIException.timeout("The AI provider did not respond in time or was unreachable.");
        } catch (AIException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw AIException.badResponse("The AI provider response could not be read: " + ex.getMessage());
        }
    }

    protected Map<String, Object> getJson(RestClient client, String url, Map<String, String> headers) {
        try {
            String response = client.get().uri(url).headers(h -> headers.forEach(h::set))
                    .retrieve().body(String.class);
            return parse(response);
        } catch (RuntimeException ex) {
            throw AIException.unavailable("The AI provider could not be reached: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw AIException.badResponse("The AI provider returned a non-JSON body.");
        }
    }

    @SuppressWarnings("unchecked")
    protected static Object dig(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(key);
        }
        return current;
    }

    protected static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Extracts the provider's own explanation from an error response, so a 400 says <em>why</em>.
     *
     * <p>Without this a misconfiguration reads only as "the provider returned 400", which tells an operator
     * nothing: every provider puts the real reason (an unavailable model, a temperature out of range, a token
     * ceiling) in the body. OpenAI, Anthropic and Gemini all nest it under {@code error.message}, so that is
     * tried first and a trimmed raw body is the fallback. The snippet is capped, and it is a response body —
     * never a request — so it cannot carry the API key.
     */
    private String providerDetail(org.springframework.web.client.RestClientResponseException ex) {
        String body;
        try {
            body = ex.getResponseBodyAsString();
        } catch (RuntimeException ignored) {
            return ".";
        }
        if (body == null || body.isBlank()) {
            return ".";
        }
        try {
            Object parsed = mapper.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> root) {
                Object error = root.get("error");
                Object message = error instanceof Map<?, ?> errorMap ? ((Map<?, ?>) errorMap).get("message")
                        : root.get("message");
                if (message != null && !String.valueOf(message).isBlank()) {
                    return ": " + trim(String.valueOf(message));
                }
            }
        } catch (Exception ignored) {
            // Not JSON, or not a shape we recognise — fall through to the raw snippet.
        }
        return ": " + trim(body);
    }

    private static String trim(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) + "..." : single;
    }
}
