package com.orchpilot.workflow.service;

import com.orchpilot.workflow.exception.SecretAccessException;

import java.util.List;
import java.util.Optional;

/**
 * Credential storage with encryption at rest and per-plugin access control.
 *
 * <p>The rule this exists to enforce: an API key never appears in a workflow definition, a node
 * configuration or an execution log. A workflow references a secret by name, a plugin declares which
 * name prefixes it may read, and this service is the only component that can turn a name into a value.
 */
public interface SecretService {

    /**
     * @return whether a master key is configured; writes are rejected when it is not
     */
    boolean isConfigured();

    /**
     * Reads a secret on behalf of a plugin.
     *
     * <p>Two independent checks must both pass: the plugin's declared secret scopes must cover the name,
     * which the caller enforces, and the secret's own {@code allowedPlugins} list must permit the plugin,
     * which this method enforces. Neither an over-permissive plugin nor an over-broad secret is enough on
     * its own.
     *
     * @param name     secret name
     * @param pluginId plugin requesting it, or {@code null} for an engine-internal read
     * @return the plaintext value, or empty when no such secret exists
     * @throws SecretAccessException when the secret exists but the plugin may not read it
     */
    Optional<String> read(String name, String pluginId);

    /**
     * Creates or replaces a secret.
     *
     * @param name           secret name
     * @param value          plaintext value
     * @param description    human-readable purpose
     * @param allowedPlugins plugin ids permitted to read it; empty means any plugin whose scope matches
     * @param actor          who is making the change, for the audit trail
     * @throws SecretAccessException when no master key is configured
     */
    void write(String name, String value, String description, List<String> allowedPlugins, String actor);

    /**
     * @param name  secret name
     * @param actor who is making the change
     * @return {@code true} when a secret was removed
     */
    boolean delete(String name, String actor);

    /**
     * @return secret names and metadata, never values
     */
    List<SecretSummary> list();

    /**
     * Metadata about a stored secret. Deliberately has no field that could hold the value.
     *
     * @param name           secret name
     * @param description    human-readable purpose
     * @param allowedPlugins plugin ids permitted to read it
     * @param keyId          master key that encrypted it
     * @param updatedAt      last write time, ISO-8601, or {@code null}
     * @param readCount      how many times it has been read
     */
    record SecretSummary(String name, String description, List<String> allowedPlugins, String keyId,
                         String updatedAt, long readCount) {
    }
}
