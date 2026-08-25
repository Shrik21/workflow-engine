package com.orchpilot.workflow.pluginserver;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * How this engine talks to the plugin registry.
 *
 * <p>An interface with one HTTP implementation, and the reason is not testability alone: the engine's plugin
 * manager, node registry and class loaders must not know that a registry exists over a network. Everything past
 * this boundary deals in bytes and metadata, so replacing the transport, or running an installation from a local
 * archive, is one implementation rather than a change to the loading machinery.
 *
 * <h2>Failure is a return value, not an exception, where the caller can carry on</h2>
 *
 * <p>{@link #fetchCatalog} returns a result object because an unreachable registry is a normal condition this
 * engine is required to survive: the catalogue falls back to its cache and installed plugins keep running.
 * Downloading, by contrast, throws, because there is no useful way to continue an install without the bytes.
 */
public interface PluginServerClient {

    /**
     * The outcome of a catalogue fetch.
     *
     * @param entries    the catalogue, empty when unchanged or unavailable
     * @param etag       the registry's validator, to send back next time
     * @param unchanged  true when the registry answered 304 and the caller should keep what it has
     * @param error      why the fetch failed, or null on success
     */
    record CatalogResult(List<CatalogRecords.CatalogEntry> entries, String etag, boolean unchanged,
                         String error) {

        public CatalogResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public static CatalogResult fetched(List<CatalogRecords.CatalogEntry> entries, String etag) {
            return new CatalogResult(entries, etag, false, null);
        }

        public static CatalogResult unchanged(String etag) {
            return new CatalogResult(List.of(), etag, true, null);
        }

        public static CatalogResult failed(String error) {
            return new CatalogResult(List.of(), null, false, error);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * Fetches the catalogue, conditionally.
     *
     * @param knownEtag the validator from the last successful fetch, or null to fetch unconditionally
     * @return the result, which may be a failure the caller is expected to tolerate
     */
    CatalogResult fetchCatalog(String knownEtag);

    /**
     * @param pluginId the plugin
     * @return its catalogue entry, or empty when the registry does not offer it
     */
    Optional<CatalogRecords.CatalogEntry> fetchPlugin(String pluginId);

    /**
     * Opens an archive for reading.
     *
     * <p>The caller owns the stream and must close it. Returned as a stream rather than a byte array so a large
     * archive can be hashed and written to the cache without being held in the heap twice.
     *
     * @param pluginId the plugin
     * @param version  the exact version
     * @return the archive's bytes
     * @throws PluginServerUnavailableException when the registry cannot be reached or refuses
     */
    InputStream download(String pluginId, String version);

    /**
     * @return whether the registry is configured and answered a health check
     */
    boolean isReachable();

    /** @return a description of where this client points, safe to log */
    String describe();
}
