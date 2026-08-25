package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Namespaced document storage for one plugin.
 *
 * <p>All plugin data lives in a single {@code plugin_data} collection keyed by
 * {@code (pluginId, collection, key)}, and the plugin id is supplied by the engine rather than the plugin.
 * A plugin therefore cannot read or overwrite another plugin's data, and cannot name an engine collection
 * such as {@code workflow_secrets} even by trying, because the collection name it passes is a field value
 * and never part of a collection name.
 *
 * <p>Queries are equality-only and capped. That is a deliberate restriction: an unbounded query language
 * handed to plugin code becomes an unindexed collection scan on a shared database, and eventually an
 * outage attributable to a plugin nobody suspected.
 */
public class MongoPluginDataStore implements PluginDataStore {

    private static final Logger log = LoggerFactory.getLogger(MongoPluginDataStore.class);

    /** The one collection every plugin's data shares. */
    public static final String COLLECTION = "plugin_data";

    private static final int MAX_KEY_LENGTH = 512;

    private final String pluginId;
    private final MongoTemplate mongoTemplate;
    private final boolean enabled;
    private final int maxResults;

    /**
     * @param pluginId      owning plugin
     * @param mongoTemplate database access
     * @param enabled       whether this plugin version may use the store
     * @param maxResults    ceiling on rows returned by a query
     */
    public MongoPluginDataStore(String pluginId, MongoTemplate mongoTemplate, boolean enabled,
                                int maxResults) {
        this.pluginId = pluginId;
        this.mongoTemplate = mongoTemplate;
        this.enabled = enabled;
        this.maxResults = Math.max(1, maxResults);
    }

    @Override
    public void put(String collection, String key, Map<String, Object> document) {
        requireEnabled();
        String safeCollection = requireName(collection, "collection");
        String safeKey = requireName(key, "key");
        Update update = new Update()
                .set("pluginId", pluginId)
                .set("collection", safeCollection)
                .set("key", safeKey)
                .set("data", document == null ? new LinkedHashMap<>() : new LinkedHashMap<>(document))
                .set("updatedAt", Instant.now());
        mongoTemplate.upsert(keyQuery(safeCollection, safeKey), update, COLLECTION);
    }

    @Override
    public Optional<Map<String, Object>> get(String collection, String key) {
        requireEnabled();
        String safeCollection = requireName(collection, "collection");
        String safeKey = requireName(key, "key");
        Document found = mongoTemplate.findOne(keyQuery(safeCollection, safeKey), Document.class, COLLECTION);
        return Optional.ofNullable(found).map(MongoPluginDataStore::extractData);
    }

    @Override
    public boolean delete(String collection, String key) {
        requireEnabled();
        String safeCollection = requireName(collection, "collection");
        String safeKey = requireName(key, "key");
        return mongoTemplate.remove(keyQuery(safeCollection, safeKey), COLLECTION).getDeletedCount() > 0;
    }

    @Override
    public List<Map<String, Object>> find(String collection, Map<String, Object> filter, int limit) {
        requireEnabled();
        String safeCollection = requireName(collection, "collection");
        Criteria criteria = Criteria.where("pluginId").is(pluginId).and("collection").is(safeCollection);
        Query query = Query.query(criteria);
        if (filter != null) {
            filter.forEach((field, value) -> {
                if (field != null && !field.isBlank() && !field.startsWith("$")) {
                    query.addCriteria(Criteria.where("data." + field).is(value));
                }
            });
        }
        query.limit(limit <= 0 ? maxResults : Math.min(limit, maxResults));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Document document : mongoTemplate.find(query, Document.class, COLLECTION)) {
            results.add(extractData(document));
        }
        log.trace("Plugin {} queried {}/{} and got {} document(s)", pluginId, COLLECTION, safeCollection,
                results.size());
        return results;
    }

    private Query keyQuery(String collection, String key) {
        return Query.query(Criteria.where("pluginId").is(pluginId)
                .and("collection").is(collection)
                .and("key").is(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractData(Document document) {
        Object data = document.get("data");
        return data instanceof Map ? new LinkedHashMap<>((Map<String, Object>) data) : new LinkedHashMap<>();
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new PluginSecurityException("Plugin '" + pluginId
                    + "' is not permitted to use the plugin data store");
        }
    }

    private static String requireName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Plugin data store " + what + " must not be blank");
        }
        if (value.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Plugin data store " + what + " exceeds " + MAX_KEY_LENGTH
                    + " characters");
        }
        // A dollar-prefixed value would be interpreted as an operator if it ever reached a query document.
        if (value.startsWith("$")) {
            throw new IllegalArgumentException("Plugin data store " + what + " must not start with '$'");
        }
        return value;
    }
}
