package com.orchpilot.pluginserver.security;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a caller may do to the registry.
 *
 * <h2>Why this enum is duplicated rather than shared</h2>
 *
 * <p>The workflow platform has its own {@code Permission} enum, and this looks like duplication. It is not.
 * These are two services with two authorization models, and sharing the type would mean the registry's
 * vocabulary changes whenever the workflow service adds a permission about forms or tasks, neither of which the
 * registry has any concept of. A service boundary is a place where a small amount of restatement buys
 * independence.
 *
 * <p>The names are chosen to match the claims the workflow platform already issues, so a token minted there
 * carries authorities that mean the same thing here.
 */
public enum PluginAuthority {

    /** Read plugin and version metadata. */
    PLUGIN_VIEW,

    /** Read a single version's full record. */
    PLUGIN_VERSION_READ,

    /** Read the catalogue: the feed a workflow service syncs from. */
    PLUGIN_CATALOG_READ,

    /** Download an archive's bytes. */
    PLUGIN_DOWNLOAD,

    /** Upload a new archive. This is the right to publish executable code to every workflow service. */
    PLUGIN_UPLOAD,

    /** Change a version's lifecycle state: activate, deactivate, deprecate, revoke. */
    PLUGIN_VERSION_MANAGE,

    /** Everything, including physical deletion. */
    PLUGIN_ADMIN,

    /** Physically remove a plugin or version and its stored bytes. */
    PLUGIN_DELETE;

    /**
     * What a service client is allowed.
     *
     * <p>Read and download, nothing else. A workflow service needs to learn what exists and fetch bytes; it has
     * no business uploading or changing lifecycle state, and a compromised workflow service should not be able
     * to publish a plugin to every other workflow service.
     *
     * @return the service client's authorities
     */
    public static Set<PluginAuthority> serviceClientDefaults() {
        return EnumSet.of(PLUGIN_CATALOG_READ, PLUGIN_VERSION_READ, PLUGIN_DOWNLOAD);
    }

    /**
     * What a plugin administrator is allowed.
     *
     * @return every authority except physical deletion, which is deliberately separate
     */
    public static Set<PluginAuthority> pluginAdminDefaults() {
        return EnumSet.of(PLUGIN_VIEW, PLUGIN_VERSION_READ, PLUGIN_CATALOG_READ, PLUGIN_DOWNLOAD,
                PLUGIN_UPLOAD, PLUGIN_VERSION_MANAGE);
    }

    /** @return every authority, for a full administrator */
    public static Set<PluginAuthority> adminDefaults() {
        return EnumSet.allOf(PluginAuthority.class);
    }

    /**
     * Parses an authority name defensively.
     *
     * @param value candidate, case-insensitive
     * @return the authority, or empty when this service does not recognise it
     */
    public static Optional<PluginAuthority> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(authority -> authority.name().equals(normalised)).findFirst();
    }

    /** @return the authority name Spring Security matches */
    public String authority() {
        return name();
    }
}
