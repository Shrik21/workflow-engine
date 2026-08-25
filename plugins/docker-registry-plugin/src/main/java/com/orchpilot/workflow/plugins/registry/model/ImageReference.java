package com.orchpilot.workflow.plugins.registry.model;

/**
 * A parsed container image reference, e.g. {@code ghcr.io/example/myapp:1.0.0} or
 * {@code us-central1-docker.pkg.dev/project/repo/myapp@sha256:abc…}.
 *
 * <h2>The two ambiguities this resolves</h2>
 *
 * Image references look simple and are not. Two rules do the real work:
 *
 * <ul>
 *   <li><b>Is the first segment a registry host or part of the repository?</b> {@code myapp:1} has no registry;
 *       {@code example/myapp:1} is Docker Hub's {@code example} namespace, not a host called "example". The
 *       standard test — and the one used here — is that a first segment only counts as a host when it contains
 *       a dot or a colon, or is exactly {@code localhost}.</li>
 *   <li><b>Is the trailing {@code :x} a tag or a port?</b> In {@code localhost:5000/myapp}, the colon is a port.
 *       Splitting on the last colon only counts as a tag when no {@code /} follows it.</li>
 * </ul>
 *
 * <p>A digest ({@code @sha256:…}) always wins over a tag when both are present, because it is the immutable
 * identity and the whole point of digest verification.
 */
public final class ImageReference {

    private static final String DOCKER_HUB_HOST = "registry-1.docker.io";

    private final String registry;
    private final String repository;
    private final String tag;
    private final String digest;

    private ImageReference(String registry, String repository, String tag, String digest) {
        this.registry = registry;
        this.repository = repository;
        this.tag = tag;
        this.digest = digest;
    }

    /**
     * Parses a full or partial image reference.
     *
     * @param reference e.g. {@code myregistry.azurecr.io/myapp:v1}, {@code example/myapp}, {@code myapp:latest}
     * @return the parsed parts; never null
     * @throws IllegalArgumentException when the reference is blank or has no repository
     */
    public static ImageReference parse(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("An image reference is required, e.g. namespace/name:tag");
        }
        String remainder = reference.trim();

        // A digest is unambiguous and terminal: split it off first.
        String digest = null;
        int at = remainder.indexOf('@');
        if (at >= 0) {
            digest = remainder.substring(at + 1);
            remainder = remainder.substring(0, at);
        }

        // Host, only if the first segment looks like one.
        String registry = null;
        int firstSlash = remainder.indexOf('/');
        if (firstSlash > 0) {
            String candidate = remainder.substring(0, firstSlash);
            if (looksLikeHost(candidate)) {
                registry = candidate;
                remainder = remainder.substring(firstSlash + 1);
            }
        }

        // Tag, only when the colon is not a port (no '/' after it) and no digest already claimed identity.
        String tag = null;
        int lastColon = remainder.lastIndexOf(':');
        if (lastColon >= 0 && remainder.indexOf('/', lastColon) < 0) {
            tag = remainder.substring(lastColon + 1);
            remainder = remainder.substring(0, lastColon);
        }

        if (remainder.isBlank()) {
            throw new IllegalArgumentException("Image reference '" + reference + "' has no repository part");
        }
        if (digest == null && tag == null) {
            tag = "latest";
        }
        return new ImageReference(registry, remainder, tag, digest);
    }

    private static boolean looksLikeHost(String segment) {
        return segment.indexOf('.') >= 0 || segment.indexOf(':') >= 0 || "localhost".equals(segment);
    }

    /** @return the registry host as written, or null when the reference did not name one */
    public String registry() {
        return registry;
    }

    /**
     * @return the repository path, e.g. {@code example/myapp}. For Docker Hub a bare {@code nginx} is
     *         normalised to {@code library/nginx}, which is what the v2 API actually requires.
     */
    public String repository() {
        return repository;
    }

    /** @return the repository as the Docker Hub v2 API expects it, with the implicit {@code library/} prefix. */
    public String dockerHubRepository() {
        return repository.indexOf('/') < 0 ? "library/" + repository : repository;
    }

    /** @return the tag, defaulting to {@code latest} when neither tag nor digest was given; may be null */
    public String tag() {
        return tag;
    }

    /** @return the {@code sha256:…} digest, or null */
    public String digest() {
        return digest;
    }

    /**
     * @return the digest when present, otherwise the tag — what the registry API calls a "reference" and what
     *         every manifest URL takes
     */
    public String reference() {
        return digest != null ? digest : tag;
    }

    /** @return the image name without its namespace, e.g. {@code myapp} from {@code example/myapp} */
    public String name() {
        int slash = repository.lastIndexOf('/');
        return slash < 0 ? repository : repository.substring(slash + 1);
    }

    /** @return the registry host, falling back to Docker Hub's when the reference named none */
    public String registryOrDefault() {
        return registry == null ? DOCKER_HUB_HOST : registry;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        if (registry != null) {
            text.append(registry).append('/');
        }
        text.append(repository);
        if (digest != null) {
            text.append('@').append(digest);
        } else if (tag != null) {
            text.append(':').append(tag);
        }
        return text.toString();
    }
}
