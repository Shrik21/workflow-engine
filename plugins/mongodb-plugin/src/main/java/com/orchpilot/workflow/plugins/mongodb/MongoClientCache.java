package com.orchpilot.workflow.plugins.mongodb;

import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One {@link MongoClient} per distinct connection, shared across executions.
 *
 * <h2>Why this exists at all</h2>
 *
 * A {@code MongoClient} is not a connection; it is a pool, a topology monitor and a background thread set.
 * Creating one per node execution would open and discard a pool for every workflow step, pay server selection
 * each time, and leave the driver's monitoring threads to be garbage collected under load. The driver's own
 * documentation is unambiguous that a client is meant to be long-lived, and this class is what makes that true
 * inside a plugin whose executions are otherwise stateless.
 *
 * <h2>Keyed on the whole connection, not on the host</h2>
 *
 * The key comes from {@link MongoConnectionSettings#cacheKey()}, which covers every setting that changes what
 * the connection <em>is</em> — including a hash of the password. Keying on host and database alone would mean
 * a rotated credential kept working from cache long after it was revoked, and that two nodes pointing at the
 * same server with different pool limits shared whichever pool was created first.
 *
 * <h2>Idle clients are closed</h2>
 *
 * A workflow that talks to a database once a month should not hold a pool open for a month. Clients unused for
 * the idle window are closed on the next access, which is enough: this runs inside an engine that is already
 * scheduling work, and adding a thread here to do it sooner would buy nothing.
 *
 * <p>Thread-safe. The map is concurrent, and {@code computeIfAbsent} means two executions racing for the same
 * connection produce one client.
 */
final class MongoClientCache implements AutoCloseable {

    /** Long enough to span the gap between steps of one workflow, short enough not to hold pools overnight. */
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, Entry> clients = new ConcurrentHashMap<>();
    private final Function<MongoConnectionSettings, MongoClient> factory;
    private final PluginLogger logger;
    private final Duration idleTimeout;

    MongoClientCache(PluginLogger logger) {
        this(logger, settings -> MongoClients.create(settings.clientSettings()), IDLE_TIMEOUT);
    }

    /** Visible for testing, where a real client would need a real server. */
    MongoClientCache(PluginLogger logger, Function<MongoConnectionSettings, MongoClient> factory,
                     Duration idleTimeout) {
        this.logger = logger;
        this.factory = factory;
        this.idleTimeout = idleTimeout;
    }

    /**
     * The client for these settings, creating one when there is none.
     *
     * @param settings the resolved connection
     * @return a client whose pool is shared with every other execution using the same connection
     */
    MongoClient get(MongoConnectionSettings settings) {
        evictIdle();

        String key = settings.cacheKey();
        Entry entry = clients.computeIfAbsent(key, ignored -> {
            // Never the settings' toString here at info level: it names the deployment, and one line per
            // connection is enough at debug.
            logger.debug("Opening a MongoDB client for {}", settings);
            return new Entry(factory.apply(settings));
        });
        entry.touch();
        return entry.client;
    }

    /** @return how many clients are currently held, for the health report and for tests */
    int size() {
        return clients.size();
    }

    /**
     * Closes a client and forgets it, so the next use builds a fresh one.
     *
     * <p>Called when a connection fails in a way that says the client is no longer usable — an authentication
     * failure after a password rotation, say — rather than leaving a broken pool to fail every execution.
     *
     * @param settings the connection to discard
     */
    void discard(MongoConnectionSettings settings) {
        Entry removed = clients.remove(settings.cacheKey());
        if (removed != null) {
            close(removed.client);
        }
    }

    private void evictIdle() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        for (Iterator<Map.Entry<String, Entry>> it = clients.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> entry = it.next();
            if (entry.getValue().lastUsed.isBefore(cutoff)) {
                it.remove();
                close(entry.getValue().client);
            }
        }
    }

    /**
     * Closes every client.
     *
     * <p>Called from the plugin's {@code destroy}, which the engine invokes when it unloads this version. Not
     * closing here would leak a pool and its threads into a class loader the engine is trying to discard,
     * which is how a plugin platform develops a memory leak that only shows up after a few reloads.
     */
    @Override
    public void close() {
        for (Iterator<Map.Entry<String, Entry>> it = clients.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> entry = it.next();
            it.remove();
            close(entry.getValue().client);
        }
    }

    private void close(MongoClient client) {
        try {
            client.close();
        } catch (RuntimeException ex) {
            // Nothing useful to do about a client that will not close, and throwing here would abandon the
            // remaining ones.
            logger.warn("A MongoDB client did not close cleanly: {}", ex.getClass().getSimpleName());
        }
    }

    /** A client and when it was last handed out. */
    private static final class Entry {
        private final MongoClient client;
        private volatile Instant lastUsed = Instant.now();

        private Entry(MongoClient client) {
            this.client = client;
        }

        private void touch() {
            lastUsed = Instant.now();
        }
    }
}
