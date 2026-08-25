package com.orchpilot.workflow.config;

import com.orchpilot.workflow.plugin.context.MongoIdempotencyStore;
import com.orchpilot.workflow.plugin.context.MongoPluginDataStore;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Creates the indexes that no mapped document declares.
 *
 * <p>Most collections get their indexes from {@code @Indexed} and {@code @CompoundIndex} on the model classes, with
 * {@code spring.data.mongodb.auto-index-creation} enabled. Two collections have no model class because plugins write
 * arbitrary documents into them, so their indexes have to be created explicitly:
 *
 * <ul>
 *   <li>{@code plugin_data}: a unique index on {@code (pluginId, collection, key)}. Without it, the upsert a plugin
 *       performs would be a collection scan, and concurrent writes to the same key could produce duplicates.</li>
 *   <li>{@code plugin_idempotency}: a TTL index on {@code expiresAt}. Abandoned claims must eventually disappear or
 *       the collection grows without bound; MongoDB's expiry does that without a cleanup job.</li>
 * </ul>
 *
 * <p>Index creation is idempotent in MongoDB, so this runs safely on every start. Failures are logged rather than
 * fatal: a replica whose user lacks index privileges should still serve traffic, slowly, rather than refuse to start.
 */
@Component
@Order(50)
public class MongoIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);

    /** Abandoned idempotency claims disappear a day after they expire. */
    private static final long IDEMPOTENCY_TTL_SECONDS = 86_400;

    private final MongoTemplate mongoTemplate;

    public MongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        reportDatabase();
        createPluginDataIndex();
        createIdempotencyTtlIndex();
    }

    /**
     * Logs the database the engine is actually connected to, and the collections currently in it.
     *
     * <p>Worth a startup line of its own. "The data is not in the database" is nearly always a mismatch
     * between the database the engine writes to and the one someone is looking at, and the configuration
     * alone cannot settle it: the name can come from the URI path, from
     * {@code spring.data.mongodb.database}, or from the {@code MONGODB_URI} environment variable
     * overriding both. This prints what won.
     *
     * <p>Collections are created lazily by MongoDB on first write, so an empty list here is normal on a
     * fresh installation rather than a sign of a broken connection.
     */
    private void reportDatabase() {
        try {
            String databaseName = mongoTemplate.getDb().getName();
            List<String> collections = new ArrayList<>();
            mongoTemplate.getDb().listCollectionNames().forEach(collections::add);
            Collections.sort(collections);
            log.info("Connected to MongoDB database '{}' with {} existing collection(s): {}",
                    databaseName, collections.size(),
                    collections.isEmpty() ? "none yet, they are created on first write" : collections);

            /*
             * "test" is the MongoDB driver's fallback when no database is named, so seeing it almost always
             * means the configured name never reached the driver. That has happened here before: Spring Boot
             * 4 binds the connection from spring.mongodb.*, and a URI set under the older
             * spring.data.mongodb.* prefix is ignored without complaint. The application then starts, works,
             * and writes everything into the wrong database, which is discovered much later by someone
             * wondering why a collection is empty.
             *
             * Warn rather than fail: "test" is a legitimate choice for a scratch instance, and refusing to
             * start would be worse than saying so clearly.
             */
            if ("test".equals(databaseName)) {
                log.warn("""
                        The database is named 'test', which is the driver's fallback when none is configured.
                        If that was not intended, the configured name is not reaching the driver: connection
                        settings belong under spring.mongodb.uri and spring.mongodb.database, not
                        spring.data.mongodb.*, which Spring Boot 4 ignores for the connection. Check
                        MONGODB_URI and MONGODB_DATABASE.""");
            }
            if (collections.size() > 60) {
                log.warn("This database holds {} collections, which is far more than this engine creates. "
                        + "Check that MONGODB_DATABASE points at a database dedicated to the workflow engine "
                        + "rather than one shared with another application.", collections.size());
            }
        } catch (RuntimeException ex) {
            log.warn("Could not report the MongoDB database name: {}", ex.getMessage());
        }
    }

    private void createPluginDataIndex() {
        try {
            mongoTemplate.getCollection(MongoPluginDataStore.COLLECTION).createIndex(
                    Indexes.ascending("pluginId", "collection", "key"),
                    new IndexOptions().unique(true).name("plugin_data_key"));
            log.debug("Ensured unique index on {}", MongoPluginDataStore.COLLECTION);
        } catch (RuntimeException ex) {
            log.warn("Could not create the {} index: {}", MongoPluginDataStore.COLLECTION, ex.getMessage());
        }
    }

    private void createIdempotencyTtlIndex() {
        try {
            mongoTemplate.getCollection(MongoIdempotencyStore.COLLECTION).createIndex(
                    Indexes.ascending("expiresAt"),
                    new IndexOptions().expireAfter(IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS)
                            .name("plugin_idempotency_ttl"));
            log.debug("Ensured TTL index on {}", MongoIdempotencyStore.COLLECTION);
        } catch (RuntimeException ex) {
            log.warn("Could not create the {} TTL index: {}", MongoIdempotencyStore.COLLECTION,
                    ex.getMessage());
        }
    }
}
