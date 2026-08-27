package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.FakeHttpClient;
import com.orchpilot.workflow.plugins.registry.exception.RegistryException;
import com.orchpilot.workflow.plugins.registry.model.ImageReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared {@code /v2/} data plane — the half of this plugin every provider inherits.
 *
 * <p>Exercised through {@link GenericDockerRegistryProvider} because it adds nothing of its own, so what passes
 * here is exactly what Docker Hub, ECR, ACR and Artifact Registry inherit. The load-bearing cases are the bearer
 * challenge (which every registry uses) and the digest guarantee on retag and promotion.
 */
class RegistryV2FlowTest {

    private static final String HOST = "harbor.example.com";
    private static final String V2 = "https://" + HOST + "/v2";

    private GenericDockerRegistryProvider provider(FakeHttpClient http) {
        return new GenericDockerRegistryProvider(http, 30_000, HOST, "robot", "secret", null);
    }

    @Test
    void followsTheBearerChallengeAndRetriesWithTheToken() {
        // A stateful stub: the catalogue refuses the first call with a challenge and accepts the second, which
        // is exactly the exchange every registry requires and the reason the base class retries a 401 once.
        java.util.concurrent.atomic.AtomicInteger catalogCalls = new java.util.concurrent.atomic.AtomicInteger();
        FakeHttpClient http = new FakeHttpClient()
                .on(req -> req.uri().endsWith("/_catalog?n=1000"),
                        req -> catalogCalls.getAndIncrement() == 0
                                ? new com.orchpilot.workflow.sdk.context.HttpResponseView(401,
                                        Map.of("WWW-Authenticate", List.of("Bearer realm=\"https://" + HOST
                                                + "/service/token\",service=\"harbor\"")), "", 1)
                                : new com.orchpilot.workflow.sdk.context.HttpResponseView(200, Map.of(),
                                        "{\"repositories\":[\"team/api\",\"team/web\"]}", 1))
                .on("GET https://" + HOST + "/service/token", 200, "{\"token\":\"issued-token\"}");

        assertThat(provider(http).listRepositories()).containsExactly("team/api", "team/web");

        // It really did fetch a token and really did retry.
        assertThat(http.countMatching("/service/token")).isEqualTo(1);
        assertThat(catalogCalls.get()).isEqualTo(2);
        assertThat(http.lastMatching("/_catalog").headers().get("Authorization"))
                .isEqualTo("Bearer issued-token");
    }

    @Test
    void listsTagsAndResolvesADigestFromTheHeader() {
        FakeHttpClient http = new FakeHttpClient()
                .on("GET " + V2 + "/team/api/tags/list", 200, "{\"name\":\"team/api\",\"tags\":[\"1.0\",\"1.1\"]}")
                .on("HEAD " + V2 + "/team/api/manifests/1.1", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:aaa")), "");

        GenericDockerRegistryProvider provider = provider(http);
        assertThat(provider.listTags("team/api")).containsExactly("1.0", "1.1");
        assertThat(provider.getDigest(ImageReference.parse("team/api:1.1"))).isEqualTo("sha256:aaa");
    }

    @Test
    void existsIsFalseOnNotFoundButPropagatesRealFailures() {
        FakeHttpClient missing = new FakeHttpClient()
                .on("HEAD " + V2 + "/team/api/manifests/nope", 404, "")
                .on("GET " + V2 + "/team/api/manifests/nope", 404, "");
        assertThat(provider(missing).exists(ImageReference.parse("team/api:nope"))).isFalse();

        // A 403 is a permissions problem, not an answer to "does it exist" — it must not be reported as false.
        FakeHttpClient denied = new FakeHttpClient()
                .on("HEAD " + V2 + "/team/api/manifests/1.0", 403, "{\"errors\":[{\"code\":\"DENIED\"}]}");
        assertThatThrownBy(() -> provider(denied).exists(ImageReference.parse("team/api:1.0")))
                .isInstanceOf(RegistryException.class)
                .satisfies(ex -> assertThat(((RegistryException) ex).errorCode())
                        .isEqualTo("AUTHORIZATION_FAILED"));
    }

    @Test
    void getImageFlattensManifestMetadataIncludingTotalSize() {
        String manifest = "{\"schemaVersion\":2,"
                + "\"mediaType\":\"application/vnd.docker.distribution.manifest.v2+json\","
                + "\"config\":{\"digest\":\"sha256:cfg\",\"size\":1000},"
                + "\"layers\":[{\"size\":500},{\"size\":700}]}";
        FakeHttpClient http = new FakeHttpClient()
                .on("GET " + V2 + "/team/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:img")), manifest);

        Map<String, Object> image = provider(http).getImage(ImageReference.parse("team/api:1.0"));

        assertThat(image).containsEntry("digest", "sha256:img");
        assertThat(image).containsEntry("layerCount", 2);
        assertThat(image).containsEntry("sizeBytes", 1200L);
        assertThat(image).containsEntry("configDigest", "sha256:cfg");
    }

    @Test
    void getImageReportsPlatformsForAMultiArchitectureImage() {
        String index = "{\"schemaVersion\":2,"
                + "\"mediaType\":\"application/vnd.oci.image.index.v1+json\","
                + "\"manifests\":[{\"digest\":\"sha256:amd\",\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}},"
                + "{\"digest\":\"sha256:arm\",\"platform\":{\"architecture\":\"arm64\",\"os\":\"linux\"}}]}";
        FakeHttpClient http = new FakeHttpClient()
                .on("GET " + V2 + "/team/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:list")), index);

        Map<String, Object> image = provider(http).getImage(ImageReference.parse("team/api:1.0"));

        assertThat(image).containsEntry("multiArchitecture", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> platforms = (List<Map<String, Object>>) image.get("platforms");
        assertThat(platforms).hasSize(2);
        assertThat(platforms.get(0)).containsEntry("architecture", "amd64");
    }

    @Test
    void retagPreservesTheDigestAndEchoesTheOriginalMediaType() {
        String mediaType = "application/vnd.docker.distribution.manifest.v2+json";
        FakeHttpClient http = new FakeHttpClient()
                .on("HEAD " + V2 + "/team/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:same")), "")
                .on("GET " + V2 + "/team/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:same"),
                                "Content-Type", List.of(mediaType)), "{\"schemaVersion\":2}")
                .on("PUT " + V2 + "/team/api/manifests/stable", 201, "")
                .on("HEAD " + V2 + "/team/api/manifests/stable", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:same")), "");

        String digest = provider(http).retag(ImageReference.parse("team/api:1.0"), "stable");

        assertThat(digest).isEqualTo("sha256:same");
        // The media type must be echoed exactly on the PUT: the digest is computed over content AND media
        // type, so sending the wrong one would silently produce a different image identity.
        assertThat(http.lastMatching("PUT", "/manifests/stable").headers())
                .containsEntry("Content-Type", mediaType);
    }

    @Test
    void promotionRefusesToProceedIfTheDigestWouldChange() {
        FakeHttpClient http = new FakeHttpClient()
                .on("HEAD " + V2 + "/dev/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:source")), "")
                .on("GET " + V2 + "/dev/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:source")), "{\"schemaVersion\":2}")
                .on("PUT " + V2 + "/prod/api/manifests/1.0", 201, "")
                // The target resolves to different content — promoting it would ship a different image.
                .on("HEAD " + V2 + "/prod/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:different")), "");

        assertThatThrownBy(() -> provider(http)
                .copyTag(ImageReference.parse("dev/api:1.0"), "prod/api", "1.0"))
                .isInstanceOf(RegistryException.class)
                .satisfies(ex -> assertThat(((RegistryException) ex).errorCode()).isEqualTo("DIGEST_MISMATCH"));
    }

    @Test
    void deleteResolvesATagToItsDigestBecauseTheApiDeletesByDigestOnly() {
        FakeHttpClient http = new FakeHttpClient()
                .on("HEAD " + V2 + "/team/api/manifests/1.0", 200,
                        Map.of("Docker-Content-Digest", List.of("sha256:target")), "")
                .on("DELETE " + V2 + "/team/api/manifests/sha256", 202, "");

        provider(http).deleteImage(ImageReference.parse("team/api:1.0"));

        // The tag was resolved to a digest first, and the DELETE addressed the digest — never the tag.
        assertThat(http.lastMatching("DELETE", "/manifests/").uri()).contains("sha256");
        assertThat(http.lastMatching("DELETE", "/manifests/").uri()).doesNotContain("manifests/1.0");
    }

    @Test
    void unsupportedOperationsSaySoRatherThanFailingObscurely() {
        assertThatThrownBy(() -> provider(new FakeHttpClient()).search("nginx", 10))
                .isInstanceOf(RegistryException.class)
                .satisfies(ex -> assertThat(((RegistryException) ex).errorCode())
                        .isEqualTo("OPERATION_NOT_SUPPORTED"));
    }

    @Test
    void parsesABearerChallengeIntoItsParts() {
        Map<String, String> parts = AbstractRegistryProvider.parseChallenge(
                "Bearer realm=\"https://auth.docker.io/token\",service=\"registry.docker.io\","
                        + "scope=\"repository:library/nginx:pull\"");

        assertThat(parts).containsEntry("realm", "https://auth.docker.io/token");
        assertThat(parts).containsEntry("service", "registry.docker.io");
        assertThat(parts).containsEntry("scope", "repository:library/nginx:pull");
    }
}
