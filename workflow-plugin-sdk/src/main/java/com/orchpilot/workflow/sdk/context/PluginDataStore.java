package com.orchpilot.workflow.sdk.context;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Namespaced document storage for plugins that need to keep state between executions, such as an
 * OAuth token cache or a cursor for an incremental sync.
 *
 * <p>Every logical collection name a plugin uses is prefixed with its plugin id by the engine, so a
 * plugin can neither read nor overwrite another plugin's data, and cannot touch engine collections.
 * This is a deliberately small surface: plugins get a key-value document store, not a
 * {@code MongoTemplate}.
 *
 * @since 1.0.0
 */
public interface PluginDataStore {

    /**
     * Inserts or replaces a document.
     *
     * @param collection logical collection name, namespaced by the engine
     * @param key        document key, unique within the collection
     * @param document   document content; must not contain secrets
     */
    void put(String collection, String key, Map<String, Object> document);

    /**
     * @param collection logical collection name
     * @param key        document key
     * @return the document, or empty when absent
     */
    Optional<Map<String, Object>> get(String collection, String key);

    /**
     * @param collection logical collection name
     * @param key        document key
     * @return {@code true} when a document was removed
     */
    boolean delete(String collection, String key);

    /**
     * Equality-only query, deliberately limited to keep plugin access predictable and indexable.
     *
     * @param collection logical collection name
     * @param filter     field/value pairs that must all match; {@code null} matches everything
     * @param limit      maximum documents to return; the engine caps this
     * @return matching documents, never {@code null}
     */
    List<Map<String, Object>> find(String collection, Map<String, Object> filter, int limit);
}
