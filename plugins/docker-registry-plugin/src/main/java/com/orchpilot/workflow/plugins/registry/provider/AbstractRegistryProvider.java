package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.ImageReference;
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
 * The Docker Registry HTTP API v2 data plane, shared by every provider.
 *
 * <h2>Why one base class covers five registries</h2>
 *
 * Docker Hub, ECR, ACR, Artifact Registry and any OCI-compatible registry all speak the <em>identical</em>
 * {@code /v2/} API for catalogues, tags, manifests and digests. What differs is only how you obtain a token and
 * which host you send it to. That is what makes this abstraction real rather than a facade: the shared half is
 * genuinely shared, and a subclass supplies authentication plus the handful of management operations
 * ({@code createRepository}, {@code search}) that live outside {@code /v2/} on provider-specific APIs.
 *
 * <h2>The bearer challenge</h2>
 *
 * Registries answer an unauthenticated {@code /v2/} request with {@code 401} and a
 * {@code WWW-Authenticate: Bearer realm="…",service="…",scope="…"} header describing where to get a token for
 * the resource being addressed. Tokens are therefore per-scope, not per-session; this class performs that
 * exchange on demand and caches by scope, which is what makes a listing followed by a manifest read work without
 * re-authenticating for every call.
 */
public abstract class AbstractRegistryProvider implements ContainerRegistryProvider {

    /** Both media types, so a registry returns whichever manifest form it stores rather than a 404. */
    protected static final String MANIFEST_ACCEPT = String.join(", ",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.oci.image.index.v1+json");

    private static final String DIGEST_HEADER = "Docker-Content-Digest";

    protected final PluginHttpClient http;
    protected final long timeoutMillis;

    /** Bearer tokens by scope. Scoped, because a registry issues a token for one resource at a time. */
    private final Map<String, String> tokenByScope = new LinkedHashMap<>();

    protected AbstractRegistryProvider(PluginHttpClient http, long timeoutMillis) {
        this.http = http;
        this.timeoutMillis = timeoutMillis <= 0 ? 60_000 : timeoutMillis;
    }

    // ------------------------------------------------------------------ provider hooks

    /** @return the registry host, e.g. {@code registry-1.docker.io} or {@code myreg.azurecr.io} */
    protected abstract String registryHost();

    /**
     * @return the {@code Authorization} value to use for the token exchange itself, typically
     *         {@code Basic base64(user:password)}; null when the registry issues anonymous tokens
     */
    protected abstract String basicAuthorization();

    /**
     * @return an {@code Authorization} value to use directly, skipping the challenge flow, for providers that
     *         issue their own bearer tokens; null to use the standard challenge
     */
    protected String directAuthorization() {
        return null;
    }

    // ------------------------------------------------------------------ v2 data plane

    @Override
    public Map<String, Object> login() {
        // A bare /v2/ call is the registry's own liveness-and-credentials probe.
        request("GET", v2("/"), scopeForCatalog(), null, null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("registry", registryHost());
        details.put("provider", type().name());
        details.put("authenticated", true);
        return details;
    }

    @Override
    public List<String> listRepositories() {
        Map<String, Object> body = json(request("GET", v2("/_catalog?n=1000"), scopeForCatalog(), null, null));
        return strings(body.get("repositories"));
    }

    @Override
    public List<String> listTags(String repository) {
        Map<String, Object> body = json(request("GET", v2("/" + repository + "/tags/list?n=1000"),
                scopeForRepository(repository, "pull"), null, null));
        return strings(body.get("tags"));
    }

    @Override
    public List<Map<String, Object>> listImages(String repository) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (String tag : listTags(repository)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tag", tag);
            try {
                entry.put("digest", getDigest(ImageReference.parse(repository + ":" + tag)));
            } catch (RegistryException ex) {
                // A tag can vanish between listing and resolving; report it rather than failing the whole list.
                entry.put("digest", null);
                entry.put("error", ex.errorCode());
            }
            images.add(entry);
        }
        return images;
    }

    @Override
    public Map<String, Object> getManifest(ImageReference image) {
        String repository = repositoryOf(image);
        HttpResponseView response = request("GET",
                v2("/" + repository + "/manifests/" + enc(image.reference())),
                scopeForRepository(repository, "pull"), MANIFEST_ACCEPT, null);
        Map<String, Object> manifest = json(response);
        String digest = response.firstHeader(DIGEST_HEADER);
        if (digest != null) {
            manifest.put("digest", digest);
        }
        return manifest;
    }

    @Override
    public String getDigest(ImageReference image) {
        String repository = repositoryOf(image);
        HttpResponseView response = request("HEAD",
                v2("/" + repository + "/manifests/" + enc(image.reference())),
                scopeForRepository(repository, "pull"), MANIFEST_ACCEPT, null);
        String digest = response.firstHeader(DIGEST_HEADER);
        if (digest == null || digest.isBlank()) {
            // Some registries omit the header on HEAD; fall back to the body, which always carries it.
            Object fromBody = getManifest(image).get("digest");
            digest = fromBody == null ? null : String.valueOf(fromBody);
        }
        if (digest == null || digest.isBlank()) {
            throw new RegistryException("NOT_FOUND",
                    "The registry did not return a digest for " + image, false);
        }
        return digest;
    }

    @Override
    public boolean exists(ImageReference image) {
        try {
            getDigest(image);
            return true;
        } catch (RegistryException ex) {
            if ("NOT_FOUND".equals(ex.errorCode())) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public Map<String, Object> getImage(ImageReference image) {
        Map<String, Object> manifest = getManifest(image);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("registry", registryHost());
        view.put("repository", repositoryOf(image));
        view.put("image", image.name());
        view.put("tag", image.tag());
        view.put("digest", manifest.get("digest"));
        view.put("mediaType", manifest.get("mediaType"));
        view.put("schemaVersion", manifest.get("schemaVersion"));

        Object config = manifest.get("config");
        if (config instanceof Map<?, ?> configMap) {
            view.put("configDigest", configMap.get("digest"));
            view.put("configSize", configMap.get("size"));
        }
        // Total size is the config plus every layer — what an operator means by "how big is this image".
        long total = 0;
        Object layers = manifest.get("layers");
        if (layers instanceof List<?> layerList) {
            view.put("layerCount", layerList.size());
            for (Object layer : layerList) {
                if (layer instanceof Map<?, ?> layerMap && layerMap.get("size") instanceof Number size) {
                    total += size.longValue();
                }
            }
        }
        if (total > 0) {
            view.put("sizeBytes", total);
        }
        // A manifest list (multi-architecture image) carries its platforms instead of layers.
        Object manifests = manifest.get("manifests");
        if (manifests instanceof List<?> entries) {
            List<Map<String, Object>> platforms = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> entryMap && entryMap.get("platform") instanceof Map<?, ?> p) {
                    Map<String, Object> platform = new LinkedHashMap<>();
                    platform.put("architecture", p.get("architecture"));
                    platform.put("os", p.get("os"));
                    platform.put("digest", entryMap.get("digest"));
                    platforms.add(platform);
                }
            }
            view.put("multiArchitecture", true);
            view.put("platforms", platforms);
        }
        return view;
    }

    @Override
    public String retag(ImageReference image, String newTag) {
        String repository = repositoryOf(image);
        String digestBefore = getDigest(image);
        HttpResponseView manifest = request("GET",
                v2("/" + repository + "/manifests/" + enc(image.reference())),
                scopeForRepository(repository, "pull"), MANIFEST_ACCEPT, null);
        String mediaType = manifest.firstHeader("Content-Type");

        putManifest(repository, newTag, manifest.body(), mediaType);

        String digestAfter = getDigest(ImageReference.parse(repository + ":" + newTag));
        verifySameDigest(digestBefore, digestAfter, "retag");
        return digestAfter;
    }

    @Override
    public String copyTag(ImageReference source, String targetRepository, String targetTag) {
        String sourceRepository = repositoryOf(source);
        String digestBefore = getDigest(source);
        HttpResponseView manifest = request("GET",
                v2("/" + sourceRepository + "/manifests/" + enc(source.reference())),
                scopeForRepository(sourceRepository, "pull"), MANIFEST_ACCEPT, null);
        String mediaType = manifest.firstHeader("Content-Type");

        putManifest(targetRepository, targetTag, manifest.body(), mediaType);

        String digestAfter = getDigest(ImageReference.parse(targetRepository + ":" + targetTag));
        verifySameDigest(digestBefore, digestAfter, "promotion");
        return digestAfter;
    }

    @Override
    public void deleteImage(ImageReference image) {
        String repository = repositoryOf(image);
        // The v2 API deletes by digest, never by tag: deleting "a tag" means deleting the manifest it names.
        String digest = image.digest() != null ? image.digest() : getDigest(image);
        request("DELETE", v2("/" + repository + "/manifests/" + enc(digest)),
                scopeForRepository(repository, "delete"), null, null);
    }

    @Override
    public void createRepository(String repository) {
        throw RegistryException.notSupported(type().displayName(), "create repository");
    }

    @Override
    public void deleteRepository(String repository) {
        throw RegistryException.notSupported(type().displayName(), "delete repository");
    }

    @Override
    public List<Map<String, Object>> search(String query, int limit) {
        throw RegistryException.notSupported(type().displayName(), "search");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Re-puts a manifest under a new tag. The original {@code Content-Type} must be echoed exactly: the digest
     * is computed over the bytes <em>and</em> the media type, so sending the wrong one produces a different
     * digest and silently breaks the "same image" guarantee this operation exists to provide.
     */
    private void putManifest(String repository, String tag, String body, String mediaType) {
        request("PUT", v2("/" + repository + "/manifests/" + enc(tag)),
                scopeForRepository(repository, "push,pull"), null, body,
                mediaType == null ? "application/vnd.docker.distribution.manifest.v2+json" : mediaType);
    }

    private void verifySameDigest(String before, String after, String what) {
        if (before != null && after != null && !before.equals(after)) {
            throw new RegistryException("DIGEST_MISMATCH",
                    "The " + what + " produced digest " + after + " but the source was " + before
                            + ". The image content would not be identical, so the operation is unsafe.", false);
        }
    }

    protected String repositoryOf(ImageReference image) {
        return image.repository();
    }

    protected String v2(String path) {
        return "https://" + registryHost() + "/v2" + path;
    }

    protected String scopeForCatalog() {
        return "registry:catalog:*";
    }

    protected String scopeForRepository(String repository, String actions) {
        return "repository:" + repository + ":" + actions;
    }

    protected static String enc(String value) {
        // Path segments only: a tag or digest never contains a slash, so encoding the whole value is safe.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    protected static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> json(HttpResponseView response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = Json.parse(body);
        return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
    }

    protected static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ transport

    protected HttpResponseView request(String method, String url, String scope, String accept, String body) {
        return request(method, url, scope, accept, body, "application/json");
    }

    /**
     * Performs a registry call, obtaining a scoped bearer token on demand when challenged.
     *
     * <p>A {@code 401} is not treated as a failure on the first attempt: it is the registry telling us where to
     * get a token for this specific resource. Only a second {@code 401} — after presenting a token — is a real
     * authentication failure.
     */
    protected HttpResponseView request(String method, String url, String scope, String accept, String body,
                                       String contentType) {
        HttpResponseView response = send(method, url, authorizationFor(scope), accept, body, contentType);
        if (response.statusCode() == 401) {
            String challenge = response.firstHeader("WWW-Authenticate");
            String token = challenge == null ? null : obtainToken(challenge, scope);
            if (token != null) {
                tokenByScope.put(scope, "Bearer " + token);
                response = send(method, url, "Bearer " + token, accept, body, contentType);
            }
        }
        if (!response.isSuccess()) {
            throw RegistryException.of(response, url.substring(url.indexOf("/v2") + 3));
        }
        return response;
    }

    private String authorizationFor(String scope) {
        String direct = directAuthorization();
        if (direct != null) {
            return direct;
        }
        String cached = tokenByScope.get(scope);
        return cached != null ? cached : basicAuthorization();
    }

    private HttpResponseView send(String method, String url, String authorization, String accept, String body,
                                  String contentType) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, url).timeoutMillis(timeoutMillis);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        builder.header("Accept", accept == null ? "application/json" : accept);
        if (body != null) {
            builder.body(body).header("Content-Type", contentType);
        }
        return http.execute(builder.build());
    }

    /**
     * Exchanges credentials for a scoped token, following the realm the registry named in its challenge.
     *
     * @param challenge the raw {@code WWW-Authenticate} header
     * @param scope     the scope to request; the challenge's own scope wins when it supplies one
     * @return the token, or null when the challenge was not a Bearer challenge we can satisfy
     */
    private String obtainToken(String challenge, String scope) {
        if (!challenge.toLowerCase(java.util.Locale.ROOT).startsWith("bearer")) {
            return null;
        }
        Map<String, String> parts = parseChallenge(challenge);
        String realm = parts.get("realm");
        if (realm == null) {
            return null;
        }
        StringBuilder url = new StringBuilder(realm);
        url.append(realm.contains("?") ? '&' : '?');
        if (parts.get("service") != null) {
            url.append("service=").append(enc(parts.get("service"))).append('&');
        }
        String effectiveScope = parts.getOrDefault("scope", scope);
        if (effectiveScope != null) {
            url.append("scope=").append(enc(effectiveScope));
        }

        HttpResponseView response = send("GET", url.toString(), basicAuthorization(), "application/json",
                null, "application/json");
        if (!response.isSuccess()) {
            throw RegistryException.authentication("the token endpoint returned HTTP " + response.statusCode());
        }
        Map<String, Object> body = json(response);
        // Registries variously call it token or access_token; both mean the same thing here.
        Object token = body.get("token") != null ? body.get("token") : body.get("access_token");
        return token == null ? null : String.valueOf(token);
    }

    /** Parses {@code Bearer realm="https://…",service="…",scope="…"} into its parts. */
    static Map<String, String> parseChallenge(String challenge) {
        Map<String, String> parts = new LinkedHashMap<>();
        String remainder = challenge.substring(challenge.indexOf(' ') + 1);
        for (String piece : remainder.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            int equals = piece.indexOf('=');
            if (equals > 0) {
                String key = piece.substring(0, equals).trim();
                String value = piece.substring(equals + 1).trim().replaceAll("^\"|\"$", "");
                parts.put(key, value);
            }
        }
        return parts;
    }
}
