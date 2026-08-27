package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.ImageReference;
import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Docker Hub.
 *
 * <p>Two hosts, because Docker Hub splits its API in two and the split is not optional: {@code registry-1.docker.io}
 * serves the standard {@code /v2/} data plane (manifests, tags, digests), while repository listing, search and
 * repository management live on {@code hub.docker.com}. Docker Hub does <em>not</em> implement {@code /v2/_catalog}
 * at all, so {@link #listRepositories()} must go to the Hub API or return nothing useful.
 *
 * <p>An unqualified repository like {@code nginx} means {@code library/nginx} to the v2 API; that normalisation is
 * applied here rather than being pushed onto the workflow author.
 */
public class DockerHubRegistryProvider extends AbstractRegistryProvider {

    private static final String REGISTRY_HOST = "registry-1.docker.io";
    private static final String HUB_API = "https://hub.docker.com/v2";

    private final String username;
    private final String token;

    public DockerHubRegistryProvider(PluginHttpClient http, long timeoutMillis, String username, String token) {
        super(http, timeoutMillis);
        this.username = username;
        this.token = token;
    }

    @Override
    public RegistryProviderType type() {
        return RegistryProviderType.DOCKER_HUB;
    }

    @Override
    protected String registryHost() {
        return REGISTRY_HOST;
    }

    @Override
    protected String basicAuthorization() {
        // Anonymous pulls of public images are legitimate and useful, so absent credentials is not an error.
        return username == null || username.isBlank() ? null : basic(username, token);
    }

    @Override
    protected String repositoryOf(ImageReference image) {
        return image.dockerHubRepository();
    }

    @Override
    public List<String> listRepositories() {
        if (username == null || username.isBlank()) {
            throw new RegistryException("AUTHENTICATION_FAILED",
                    "Listing Docker Hub repositories needs a username; only anonymous image reads work without "
                            + "one.", false);
        }
        HttpResponseView response = hub("GET",
                HUB_API + "/repositories/" + enc(username) + "?page_size=100");
        List<String> repositories = new ArrayList<>();
        Object results = json(response).get("results");
        if (results instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("name") != null) {
                    repositories.add(username + "/" + map.get("name"));
                }
            }
        }
        return repositories;
    }

    @Override
    public List<Map<String, Object>> search(String query, int limit) {
        HttpResponseView response = hub("GET", HUB_API + "/search/repositories?query=" + enc(query)
                + "&page_size=" + Math.max(1, Math.min(limit, 100)));
        List<Map<String, Object>> hits = new ArrayList<>();
        Object results = json(response).get("results");
        if (results instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("name", map.get("repo_name"));
                    hit.put("description", map.get("short_description"));
                    hit.put("stars", map.get("star_count"));
                    hit.put("official", map.get("is_official"));
                    hits.add(hit);
                }
            }
        }
        return hits;
    }

    @Override
    public void deleteRepository(String repository) {
        String path = repository.contains("/") ? repository : username + "/" + repository;
        hub("DELETE", HUB_API + "/repositories/" + path + "/");
    }

    /** The Hub API takes the same credentials but is a different host, so it bypasses the v2 challenge flow. */
    private HttpResponseView hub(String method, String url) {
        com.orchpilot.workflow.sdk.context.HttpRequestSpec.Builder builder =
                com.orchpilot.workflow.sdk.context.HttpRequestSpec.builder(method, url)
                        .header("Accept", "application/json")
                        .timeoutMillis(timeoutMillis);
        if (username != null && !username.isBlank()) {
            builder.header("Authorization", basic(username, token));
        }
        HttpResponseView response = http.execute(builder.build());
        if (!response.isSuccess()) {
            throw RegistryException.of(response, "Docker Hub API " + url);
        }
        return response;
    }
}
