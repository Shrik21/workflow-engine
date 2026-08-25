package com.orchpilot.workflow.plugins.jira;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Jira REST wire, and the one place the Cloud/Server difference is handled.
 *
 * <h2>Two deployments, three real differences</h2>
 *
 * "Jira Cloud and Server where compatible" hides three concrete divergences, all absorbed here so no operation
 * has to know which it is talking to:
 *
 * <ol>
 *   <li><b>API version.</b> Cloud is {@code /rest/api/3}, Server/DC is {@code /rest/api/2}.</li>
 *   <li><b>Rich text.</b> Cloud v3 requires <em>Atlassian Document Format</em> — a structured JSON document —
 *       for descriptions and comments. Sending a plain string, which is what every workflow author naturally
 *       supplies, fails with an opaque 400. Server v2 wants exactly that plain string. {@link #richText} converts
 *       per deployment, so an author writes text and it works on both.</li>
 *   <li><b>Authentication.</b> Cloud uses Basic with {@code email:apiToken}; Server/DC uses a Bearer personal
 *       access token.</li>
 * </ol>
 *
 * <p>The Agile API ({@code /rest/agile/1.0}) is shared by both and is versioned separately, which is why sprints
 * and boards route through {@link #agile}.
 */
public class JiraClient {

    /** Which Jira this connection points at. */
    public enum Deployment {
        CLOUD,
        SERVER;

        static Deployment parse(String value) {
            return value != null && value.trim().equalsIgnoreCase("SERVER") ? SERVER : CLOUD;
        }
    }

    private final PluginHttpClient http;
    private final String baseUrl;
    private final String authorization;
    private final Deployment deployment;
    private final long timeoutMillis;

    /**
     * @param baseUrl    e.g. {@code https://company.atlassian.net}
     * @param credential Cloud: {@code email:apiToken}. Server/DC: the personal access token on its own.
     */
    public JiraClient(PluginHttpClient http, String baseUrl, String credential, Deployment deployment,
                      long timeoutMillis) {
        this.http = http;
        this.baseUrl = normalise(baseUrl);
        this.deployment = deployment;
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
        this.authorization = authorization(credential, deployment);
    }

    private static String normalise(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new JiraException("JIRA_MISCONFIGURED",
                    "The Jira connection needs a base URL, e.g. https://company.atlassian.net.", false);
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        return trimmed.startsWith("http") ? trimmed : "https://" + trimmed;
    }

    private static String authorization(String credential, Deployment deployment) {
        if (credential == null || credential.isBlank()) {
            throw new JiraException("JIRA_AUTHENTICATION_FAILED",
                    "The Jira credentials secret is empty.", false);
        }
        if (deployment == Deployment.SERVER) {
            // A Data Center personal access token is a bearer token, not half of a Basic pair.
            return "Bearer " + credential.trim();
        }
        if (!credential.contains(":")) {
            throw new JiraException("JIRA_AUTHENTICATION_FAILED",
                    "Jira Cloud credentials must be stored as 'email:apiToken' in one secret.", false);
        }
        return "Basic " + Base64.getEncoder()
                .encodeToString(credential.trim().getBytes(StandardCharsets.UTF_8));
    }

    public Deployment deployment() {
        return deployment;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** @return the REST base for the platform API, version-correct for this deployment */
    public String api() {
        return baseUrl + "/rest/api/" + (deployment == Deployment.CLOUD ? "3" : "2");
    }

    /** @return the REST base for the Jira Software (Agile) API, which both deployments version as 1.0 */
    public String agile() {
        return baseUrl + "/rest/agile/1.0";
    }

    /**
     * Wraps plain text the way the target deployment needs it.
     *
     * <p>Cloud v3 rejects a bare string for {@code description} or a comment {@code body}; it needs an ADF
     * document. Rather than make every workflow author learn ADF, text is converted here, one paragraph per
     * line so line breaks survive the round trip.
     *
     * @param text plain text from a workflow, possibly multi-line
     * @return an ADF document for Cloud, or the original string for Server
     */
    public Object richText(String text) {
        if (text == null) {
            return null;
        }
        if (deployment == Deployment.SERVER) {
            return text;
        }
        List<Object> paragraphs = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            Map<String, Object> paragraph = new LinkedHashMap<>();
            paragraph.put("type", "paragraph");
            // An empty paragraph must have no content array at all; ADF rejects an empty text node.
            if (!line.isEmpty()) {
                paragraph.put("content", List.of(Map.of("type", "text", "text", line)));
            }
            paragraphs.add(paragraph);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", "doc");
        document.put("version", 1);
        document.put("content", paragraphs);
        return document;
    }

    /**
     * Reads text back out of whatever shape the deployment stored it in, so a workflow reading a description
     * gets a string on both Cloud and Server.
     */
    @SuppressWarnings("unchecked")
    public static String plainText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Map<?, ?> node) {
            Object text = ((Map<String, Object>) node).get("text");
            if (text != null) {
                return String.valueOf(text);
            }
            Object content = ((Map<String, Object>) node).get("content");
            if (content instanceof List<?> children) {
                StringBuilder out = new StringBuilder();
                for (Object child : children) {
                    String piece = plainText(child);
                    if (piece != null) {
                        if (out.length() > 0 && "paragraph".equals(((Map<String, Object>) node).get("type"))) {
                            out.append('\n');
                        }
                        out.append(piece);
                    }
                }
                return out.toString();
            }
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------ transport

    public Map<String, Object> get(String url, String what) {
        return object(send("GET", url, null, null, what));
    }

    public Map<String, Object> post(String url, Object body, String what) {
        return object(send("POST", url, Json.write(body), "application/json", what));
    }

    public Map<String, Object> put(String url, Object body, String what) {
        return object(send("PUT", url, Json.write(body), "application/json", what));
    }

    public void delete(String url, String what) {
        send("DELETE", url, null, null, what);
    }

    /**
     * Uploads a text file as a Jira attachment.
     *
     * <p>Jira's attachment endpoint takes {@code multipart/form-data}, which the plugin HTTP client cannot build
     * — it sends a single {@code String} body. A multipart envelope is therefore hand-assembled here, which
     * works precisely because the payload is text: a binary file would not survive being carried as a Java
     * string, which is why binary attachments are out of scope rather than merely unimplemented.
     */
    public List<Object> attachText(String issueKey, String fileName, String content) {
        String boundary = "----OrchPilotBoundary" + Long.toHexString(System.nanoTime());
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + content + "\r\n"
                + "--" + boundary + "--\r\n";

        HttpResponseView response = http.execute(HttpRequestSpec
                .builder("POST", api() + "/issue/" + enc(issueKey) + "/attachments")
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                // Jira refuses attachment uploads without this header, as XSRF protection.
                .header("X-Atlassian-Token", "no-check")
                .body(body)
                .timeoutMillis(timeoutMillis)
                .build());
        if (!response.isSuccess()) {
            throw JiraException.of(response, "attachment on " + issueKey);
        }
        Object parsed = Json.parse(response.body());
        return parsed instanceof List ? (List<Object>) parsed : List.of();
    }

    private HttpResponseView send(String method, String url, String body, String contentType, String what) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, url)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .timeoutMillis(timeoutMillis);
        if (body != null) {
            builder.body(body).header("Content-Type", contentType);
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw JiraException.of(response, what);
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(HttpResponseView response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            // 204 from a transition, an update or a delete: success with nothing to say.
            return new LinkedHashMap<>();
        }
        Object parsed = Json.parse(body);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        // A few endpoints answer with a bare array; wrap it so callers always get a map.
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("values", parsed);
        return wrapper;
    }

    public static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
