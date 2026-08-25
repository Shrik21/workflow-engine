package com.orchpilot.workflow.plugins.registry.provider;

import com.orchpilot.workflow.plugins.registry.model.ImageReference;
import com.orchpilot.workflow.plugins.registry.model.RegistryProviderType;

import java.util.List;
import java.util.Map;

/**
 * The one abstraction the plugin's dispatch layer knows about.
 *
 * <p>Everything provider-specific — how to authenticate, which host to talk to, which management API creates a
 * repository — lives behind this interface, so adding a sixth registry is a new implementation plus an enum
 * constant, with no change to the node catalogue, the dispatch path, or anything the AI Agent sees.
 *
 * <p>Implementations are created per execution and are not shared between threads.
 */
public interface ContainerRegistryProvider {

    RegistryProviderType type();

    /**
     * Verifies the credentials work.
     *
     * @return details worth showing an operator, e.g. the resolved registry host; never a credential
     */
    Map<String, Object> login();

    /** @return repository names visible to these credentials */
    List<String> listRepositories();

    /** @return the tags of one repository, most-recent-first where the provider orders them */
    List<String> listTags(String repository);

    /**
     * @param repository repository to list
     * @return one entry per tag: {@code {tag, digest}}, so a caller can verify or promote by digest
     */
    List<Map<String, Object>> listImages(String repository);

    /** @return the raw manifest document for a tag or digest */
    Map<String, Object> getManifest(ImageReference image);

    /** @return the immutable {@code sha256:…} digest a tag currently resolves to */
    String getDigest(ImageReference image);

    /** @return whether the tag or digest exists, without downloading the manifest body */
    boolean exists(ImageReference image);

    /** @return manifest metadata flattened for a workflow: digest, media type, size, architecture, os */
    Map<String, Object> getImage(ImageReference image);

    /**
     * Adds {@code newTag} to the image {@code image} already points at, by re-putting its manifest.
     *
     * @return the digest, which must be unchanged — that it is unchanged is the guarantee retagging offers
     */
    String retag(ImageReference image, String newTag);

    /**
     * Copies a manifest to another repository in the same registry.
     *
     * <p>Only valid within one registry: the blobs are already present there, so no layer is transferred. Across
     * registries the blobs would have to move, which an in-process plugin cannot do.
     *
     * @return the digest in the target repository, which must equal the source digest
     */
    String copyTag(ImageReference source, String targetRepository, String targetTag);

    /** Deletes a manifest, and with it every tag pointing at it. */
    void deleteImage(ImageReference image);

    void createRepository(String repository);

    void deleteRepository(String repository);

    /** @return search hits as {@code {name, description}}; providers without search throw not-supported */
    List<Map<String, Object>> search(String query, int limit);
}
