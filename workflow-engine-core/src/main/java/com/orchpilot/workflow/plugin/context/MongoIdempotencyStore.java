package com.orchpilot.workflow.plugin.context;

import com.orchpilot.workflow.sdk.context.IdempotencyStore;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fine-grained deduplication for plugins with external side effects.
 *
 * <p>The claim is an insert on a unique {@code _id}, not a read-then-write. That distinction is the whole
 * point: two engine instances retrying the same node at the same instant would both pass a
 * check-then-insert, and both send the email. Only the database can arbitrate, and it does so by rejecting
 * the second insert.
 *
 * <p>Claims carry an expiry so that a process that dies mid-operation does not block the key forever. An
 * expired claim is taken over rather than deleted, which keeps the takeover itself atomic.
 */
public class MongoIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(MongoIdempotencyStore.class);

    /** Collection holding plugin-level idempotency claims. */
    public static final String COLLECTION = "plugin_idempotency";

    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final String pluginId;
    private final MongoTemplate mongoTemplate;

    public MongoIdempotencyStore(String pluginId, MongoTemplate mongoTemplate) {
        this.pluginId = pluginId;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> lookup(String key) {
        Document found = findDocument(key);
        if (found == null || !STATUS_COMPLETED.equals(found.getString("status"))) {
            return Optional.empty();
        }
        Object result = found.get("result");
        return Optional.of(result instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) result)
                : new LinkedHashMap<>());
    }

    @Override
    public boolean claim(String key, Duration ttl) {
        String id = documentId(key);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl == null || ttl.isZero() ? Duration.ofMinutes(15) : ttl);

        Document document = new Document();
        document.put("_id", id);
        document.put("pluginId", pluginId);
        document.put("key", key);
        document.put("status", STATUS_CLAIMED);
        document.put("claimedAt", now);
        document.put("expiresAt", expiresAt);
        try {
            mongoTemplate.insert(document, COLLECTION);
            return true;
        } catch (DuplicateKeyException ex) {
            return takeOverIfExpired(id, now, expiresAt);
        }
    }

    @Override
    public void complete(String key, Map<String, Object> result) {
        Update update = new Update()
                .set("status", STATUS_COMPLETED)
                .set("result", result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result))
                .set("completedAt", Instant.now())
                .unset("expiresAt");
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(documentId(key))), update, COLLECTION);
    }

    @Override
    public void release(String key, String errorCode, String message) {
        // Delete rather than mark failed: a released key must be freshly claimable, and keeping failure
        // history here would duplicate what plugin_executions already records.
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(documentId(key))), COLLECTION);
        log.debug("Plugin {} released idempotency key '{}' after {}: {}", pluginId, key, errorCode, message);
    }

    /**
     * Takes over a claim whose owner appears to have died.
     *
     * <p>The expiry is part of the update's filter, so two instances racing to take over the same abandoned
     * claim still produce exactly one winner.
     */
    private boolean takeOverIfExpired(String id, Instant now, Instant newExpiry) {
        Criteria criteria = Criteria.where("_id").is(id)
                .and("status").is(STATUS_CLAIMED)
                .and("expiresAt").lt(now);
        Update update = new Update()
                .set("claimedAt", now)
                .set("expiresAt", newExpiry);
        long modified = mongoTemplate.updateFirst(Query.query(criteria), update, COLLECTION)
                .getModifiedCount();
        if (modified > 0) {
            log.info("Plugin {} took over an expired idempotency claim {}", pluginId, id);
            return true;
        }
        return false;
    }

    private Document findDocument(String key) {
        return mongoTemplate.findOne(Query.query(Criteria.where("_id").is(documentId(key))),
                Document.class, COLLECTION);
    }

    private String documentId(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }
        return pluginId + ":" + key;
    }
}
