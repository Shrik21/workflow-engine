package com.orchpilot.workflow.plugins.registry.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Image reference parsing — the part of this plugin most likely to be subtly wrong, because the format is
 * ambiguous by design. The two traps are whether a first segment is a host or a namespace, and whether a colon
 * introduces a tag or a port; both are pinned here, for every registry's URL shape.
 */
class ImageReferenceTest {

    @Test
    void parsesEachRegistrysShape() {
        ImageReference ghcr = ImageReference.parse("ghcr.io/example/myapp:1.0.0");
        assertThat(ghcr.registry()).isEqualTo("ghcr.io");
        assertThat(ghcr.repository()).isEqualTo("example/myapp");
        assertThat(ghcr.name()).isEqualTo("myapp");
        assertThat(ghcr.tag()).isEqualTo("1.0.0");

        ImageReference ecr = ImageReference.parse("123456789.dkr.ecr.us-east-1.amazonaws.com/myapp:latest");
        assertThat(ecr.registry()).isEqualTo("123456789.dkr.ecr.us-east-1.amazonaws.com");
        assertThat(ecr.repository()).isEqualTo("myapp");
        assertThat(ecr.tag()).isEqualTo("latest");

        ImageReference acr = ImageReference.parse("myregistry.azurecr.io/myapp:v1");
        assertThat(acr.registry()).isEqualTo("myregistry.azurecr.io");
        assertThat(acr.repository()).isEqualTo("myapp");

        ImageReference gar = ImageReference.parse("us-central1-docker.pkg.dev/project/repository/myapp:v1");
        assertThat(gar.registry()).isEqualTo("us-central1-docker.pkg.dev");
        assertThat(gar.repository()).isEqualTo("project/repository/myapp");
        assertThat(gar.name()).isEqualTo("myapp");
    }

    @Test
    void treatsAFirstSegmentAsAHostOnlyWhenItLooksLikeOne() {
        // 'example' is a Docker Hub namespace, not a registry — no dot, no colon.
        ImageReference hub = ImageReference.parse("example/myapp:2");
        assertThat(hub.registry()).isNull();
        assertThat(hub.repository()).isEqualTo("example/myapp");

        // 'ghcr.io' has a dot, so it is a host.
        assertThat(ImageReference.parse("ghcr.io/myapp").registry()).isEqualTo("ghcr.io");
        // localhost is the documented exception that has neither.
        assertThat(ImageReference.parse("localhost/myapp").registry()).isEqualTo("localhost");
    }

    @Test
    void distinguishesAPortFromATag() {
        // The colon here is a port: a '/' follows it, so it cannot be a tag.
        ImageReference withPort = ImageReference.parse("localhost:5000/myapp");
        assertThat(withPort.registry()).isEqualTo("localhost:5000");
        assertThat(withPort.repository()).isEqualTo("myapp");
        assertThat(withPort.tag()).isEqualTo("latest");

        ImageReference portAndTag = ImageReference.parse("registry.local:5000/team/myapp:3.1");
        assertThat(portAndTag.registry()).isEqualTo("registry.local:5000");
        assertThat(portAndTag.repository()).isEqualTo("team/myapp");
        assertThat(portAndTag.tag()).isEqualTo("3.1");
    }

    @Test
    void aDigestWinsOverATagAndBecomesTheReference() {
        ImageReference digest = ImageReference.parse(
                "ghcr.io/example/myapp@sha256:abc123");
        assertThat(digest.digest()).isEqualTo("sha256:abc123");
        assertThat(digest.tag()).isNull();
        assertThat(digest.reference()).isEqualTo("sha256:abc123");

        // Both present: the digest is the immutable identity, so it is what addresses the manifest.
        ImageReference both = ImageReference.parse("example/myapp:1.0@sha256:def456");
        assertThat(both.tag()).isEqualTo("1.0");
        assertThat(both.digest()).isEqualTo("sha256:def456");
        assertThat(both.reference()).isEqualTo("sha256:def456");
    }

    @Test
    void defaultsToLatestAndNormalisesDockerHubLibraryImages() {
        ImageReference bare = ImageReference.parse("nginx");
        assertThat(bare.tag()).isEqualTo("latest");
        assertThat(bare.repository()).isEqualTo("nginx");
        // The v2 API needs the implicit library/ prefix that the CLI hides.
        assertThat(bare.dockerHubRepository()).isEqualTo("library/nginx");
        assertThat(ImageReference.parse("example/myapp").dockerHubRepository()).isEqualTo("example/myapp");
    }

    @Test
    void rejectsUnusableReferences() {
        assertThatThrownBy(() -> ImageReference.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageReference.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageReference.parse("ghcr.io/:1.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsThroughToString() {
        assertThat(ImageReference.parse("ghcr.io/example/myapp:1.0.0")).hasToString("ghcr.io/example/myapp:1.0.0");
        assertThat(ImageReference.parse("example/myapp@sha256:abc")).hasToString("example/myapp@sha256:abc");
    }
}
