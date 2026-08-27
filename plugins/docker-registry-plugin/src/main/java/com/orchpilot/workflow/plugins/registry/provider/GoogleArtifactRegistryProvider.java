package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.auth.GoogleServiceAccountAuth;
import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.ImageReference;
import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;
import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Artifact Registry.
 *
 * <h2>The repository-path quirk</h2>
 *
 * Artifact Registry's image paths carry the GCP project and the AR repository <em>inside</em> the repository
 * portion: {@code us-central1-docker.pkg.dev/<project>/<repository>/<image>}. So a workflow author naming
 * {@code myapp} means {@code <project>/<repository>/myapp} to the v2 API. That prefixing is applied here rather
 * than being pushed onto the author, who should not have to restate configuration they already supplied.
 *
 * <p>Authentication is the service-account JWT flow; the resulting OAuth2 access token is presented directly as
 * a bearer, so the shared challenge flow is bypassed entirely.
 */
public class GoogleArtifactRegistryProvider extends AbstractRegistryProvider {

    private static final String AR_API = "https://artifactregistry.googleapis.com/v1";

    private final String projectId;
    private final String location;
    private final String repository;
    private final String serviceAccountJson;

    private String cachedToken;

    public GoogleArtifactRegistryProvider(PluginHttpClient http, long timeoutMillis, String projectId,
                                          String location, String repository, String serviceAccountJson) {
        super(http, timeoutMillis);
        this.projectId = require(projectId, "project id");
        this.location = require(location, "location, e.g. us-central1");
        this.repository = repository;
        this.serviceAccountJson = serviceAccountJson;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new RegistryException("INVALID_REQUEST", "Artifact Registry needs the " + what + ".", false);
        }
        return value.trim();
    }

    @Override
    public RegistryProviderType type() {
        return RegistryProviderType.GOOGLE_ARTIFACT_REGISTRY;
    }

    @Override
    protected String registryHost() {
        return location + "-docker.pkg.dev";
    }

    @Override
    protected String basicAuthorization() {
        return null;
    }

    @Override
    protected String directAuthorization() {
        if (cachedToken == null) {
            cachedToken = GoogleServiceAccountAuth.accessToken(serviceAccountJson, http, timeoutMillis);
        }
        return "Bearer " + cachedToken;
    }

    /** Prefixes {@code project/repository} unless the caller already supplied a fully-qualified path. */
    @Override
    protected String repositoryOf(ImageReference image) {
        return qualify(image.repository());
    }

    private String qualify(String name) {
        if (name.startsWith(projectId + "/")) {
            return name;
        }
        return repository == null || repository.isBlank()
                ? projectId + "/" + name
                : projectId + "/" + repository + "/" + name;
    }

    @Override
    public List<String> listTags(String repositoryName) {
        return super.listTags(qualify(repositoryName));
    }

    @Override
    public List<Map<String, Object>> listImages(String repositoryName) {
        return super.listImages(qualify(repositoryName));
    }

    /**
     * Lists the Docker images the Artifact Registry API knows about, which is richer than a {@code /v2/} catalog
     * and is what AR actually supports.
     */
    @Override
    public List<String> listRepositories() {
        String parent = "projects/" + projectId + "/locations/" + location;
        Map<String, Object> body = arApi("GET", AR_API + "/" + parent + "/repositories?pageSize=500");
        List<String> names = new ArrayList<>();
        if (body.get("repositories") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("name") != null) {
                    String full = String.valueOf(map.get("name"));
                    names.add(full.substring(full.lastIndexOf('/') + 1));
                }
            }
        }
        return names;
    }

    @Override
    public void createRepository(String repositoryName) {
        String parent = "projects/" + projectId + "/locations/" + location;
        arApi("POST", AR_API + "/" + parent + "/repositories?repositoryId=" + enc(repositoryName),
                Json.write(Map.of("format", "DOCKER")));
    }

    @Override
    public void deleteRepository(String repositoryName) {
        String name = "projects/" + projectId + "/locations/" + location + "/repositories/" + repositoryName;
        arApi("DELETE", AR_API + "/" + name);
    }

    private Map<String, Object> arApi(String method, String url) {
        return arApi(method, url, null);
    }

    private Map<String, Object> arApi(String method, String url, String body) {
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, url)
                .header("Authorization", directAuthorization())
                .header("Accept", "application/json")
                .timeoutMillis(timeoutMillis);
        if (body != null) {
            builder.body(body).header("Content-Type", "application/json");
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw RegistryException.of(response, "Artifact Registry " + url);
        }
        return json(response);
    }

    @Override
    public Map<String, Object> login() {
        Map<String, Object> details = new LinkedHashMap<>(super.login());
        details.put("projectId", projectId);
        details.put("location", location);
        if (repository != null && !repository.isBlank()) {
            details.put("repository", repository);
        }
        return details;
    }
}
