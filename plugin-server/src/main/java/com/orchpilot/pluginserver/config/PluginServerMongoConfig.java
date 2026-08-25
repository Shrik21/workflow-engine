package com.orchpilot.pluginserver.config;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Storage wiring, and a startup report of what it is actually wired to.
 *
 * <p>The bucket name is stated once here and used everywhere. GridFS defaults to a bucket called {@code fs},
 * which works and tells a reader nothing; {@code plugin_jars} says what the bytes are.
 */
@Configuration
public class PluginServerMongoConfig {

    private static final Logger log = LoggerFactory.getLogger(PluginServerMongoConfig.class);

    /** Where plugin archives live. Referenced by the storage layer rather than repeated as a literal. */
    public static final String JAR_BUCKET = "plugin_jars";

    /**
     * @param template the configured Mongo template
     * @return a GridFS template bound to the named bucket
     */
    @Bean
    GridFsTemplate gridFsTemplate(MongoTemplate template) {
        return new GridFsTemplate(template.getMongoDatabaseFactory(), template.getConverter(), JAR_BUCKET);
    }

    /**
     * Reports the database and creates the one index no mapped document declares.
     *
     * <p>The report exists because "the data is not in the database" is nearly always a mismatch between the
     * database a service writes to and the one somebody is looking at. Spring Boot 4 binds the connection from
     * {@code spring.mongodb.*}, and a URI under the older {@code spring.data.mongodb.*} prefix is ignored
     * without complaint, after which everything works and lands in the wrong place. This platform has already
     * lost time to exactly that, so the winning value is printed at startup.
     *
     * @param template the configured Mongo template
     * @return a runner that logs and indexes
     */
    @Bean
    ApplicationRunner reportStorage(MongoTemplate template) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                try {
                    String database = template.getDb().getName();
                    List<String> collections = new ArrayList<>();
                    template.getDb().listCollectionNames().forEach(collections::add);
                    Collections.sort(collections);
                    log.info("Plugin registry connected to MongoDB database '{}' with {} collection(s): {}",
                            database, collections.size(),
                            collections.isEmpty() ? "none yet, created on first write" : collections);

                    if ("test".equals(database)) {
                        log.warn("""
                                The database is named 'test', which is the driver's fallback when none is \
                                configured. Connection settings belong under spring.mongodb.uri and \
                                spring.mongodb.database; Spring Boot 4 ignores spring.data.mongodb.* for the \
                                connection. Check MONGODB_URI and MONGODB_DATABASE.""");
                    }

                    /*
                     * An index on the archive checksum. Not declared on a document because GridFS files are
                     * written by the driver, and it answers a question the registry genuinely asks: has anyone
                     * uploaded these exact bytes before, under another version number?
                     */
                    template.getDb().getCollection(JAR_BUCKET + ".files").createIndex(
                            Indexes.ascending("metadata.sha256"),
                            new IndexOptions().name("plugin_jar_sha256"));
                } catch (RuntimeException ex) {
                    // A replica whose user cannot create indexes should still serve traffic, slowly.
                    log.warn("Could not report storage or create the archive index: {}", ex.getMessage());
                }
            }
        };
    }
}
