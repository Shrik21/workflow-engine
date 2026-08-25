package com.orchpilot.workflow.pluginserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Keeps a local copy of the registry's catalogue.
 *
 * <h2>Never on the execution path</h2>
 *
 * <p>Nothing about running a workflow consults the registry. The catalogue is read on a schedule, on demand, and at
 * startup, and every other part of the engine reads the cache. That is a hard requirement rather than an
 * optimisation: a plugin node executing must not be able to fail because a different service is down, and an engine
 * that phoned home per execution would make the registry a participant in every workflow run in the estate.
 *
 * <h2>A failed sync is not an error</h2>
 *
 * <p>It records why, keeps the previous catalogue, and returns. The registry being unreachable is an expected
 * condition this engine is required to survive; treating it as a fault would fill the log with alarms during a
 * routine deployment of the other service.
 */
@Service
public class PluginCatalogSyncService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginCatalogSyncService.class);

    private final PluginServerClient client;
    private final CatalogCacheRepository cache;
    private final PluginServerProperties properties;

    public PluginCatalogSyncService(PluginServerClient client, CatalogCacheRepository cache,
                                    PluginServerProperties properties) {
        this.client = client;
        this.cache = cache;
        this.properties = properties;
    }

    /**
     * The result of a sync attempt.
     *
     * @param outcome  what happened
     * @param plugins  how many plugins the catalogue now holds
     * @param syncedAt when the catalogue in hand was last successfully fetched
     * @param error    why it failed, or null
     */
    public record SyncResult(Outcome outcome, int plugins, Instant syncedAt, String error) {

        /** What a sync attempt did. */
        public enum Outcome {
            /** The catalogue was fetched and replaced. */
            UPDATED,
            /** The registry answered 304; the cached catalogue is current. */
            UNCHANGED,
            /** The attempt failed and the previous catalogue is still in use. */
            FAILED,
            /** No registry is configured, so there is nothing to sync from. */
            NOT_CONFIGURED
        }

        public boolean isSuccess() {
            return outcome == Outcome.UPDATED || outcome == Outcome.UNCHANGED;
        }
    }

    /**
     * Syncs at startup, before anything asks for the catalogue.
     *
     * <p>Ordered after the plugin startup loader, so locally installed plugins are already loaded and the engine is
     * usable whether or not this succeeds.
     */
    @Override
    @Order(60)
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("No plugin registry configured; the marketplace will be empty and installed plugins "
                    + "continue to work");
            return;
        }
        if (!properties.isSyncOnStartup()) {
            log.info("Startup catalogue sync is disabled");
            return;
        }
        log.info("Syncing the plugin catalogue from {}", properties.describe());
        sync();
    }

    /**
     * Refreshes the catalogue on a schedule.
     *
     * <p>The interval is deliberately generous. A new plugin appearing in the marketplace within five minutes is
     * fine, and a shorter interval multiplied by every engine in the estate is traffic the registry has to serve for
     * no benefit. The conditional request means an unchanged catalogue costs a 304 anyway.
     */
    @Scheduled(initialDelayString = "${plugin.server.sync-interval:PT5M}",
            fixedDelayString = "${plugin.server.sync-interval:PT5M}")
    public void scheduledSync() {
        if (properties.isConfigured()) {
            sync();
        }
    }

    /**
     * Fetches the catalogue and stores it.
     *
     * @return what happened
     */
    public synchronized SyncResult sync() {
        if (!properties.isConfigured()) {
            return new SyncResult(SyncResult.Outcome.NOT_CONFIGURED, cached().getEntries().size(),
                    cached().getSyncedAt(), "No plugin registry is configured.");
        }

        CatalogCache current = cached();
        PluginServerClient.CatalogResult result = client.fetchCatalog(current.getEtag());
        current.setLastAttemptAt(Instant.now());

        if (!result.isSuccess()) {
            current.setLastError(result.error());
            cache.save(current);
            log.info("Catalogue sync failed, keeping the copy from {}: {}",
                    current.getSyncedAt(), result.error());
            return new SyncResult(SyncResult.Outcome.FAILED, current.getEntries().size(),
                    current.getSyncedAt(), result.error());
        }

        current.setLastError(null);
        current.setSyncCount(current.getSyncCount() + 1);

        if (result.unchanged()) {
            /*
             * syncedAt is advanced even though nothing changed.
             *
             * It answers "when did we last confirm this is current", not "when did the contents last change",
             * and that is the question the marketplace's "last synchronised" line is really asking. Leaving it
             * alone would show a catalogue as an hour stale when it had been confirmed current a moment ago.
             */
            current.setSyncedAt(Instant.now());
            cache.save(current);
            log.debug("Catalogue unchanged; {} plugin(s) cached", current.getEntries().size());
            return new SyncResult(SyncResult.Outcome.UNCHANGED, current.getEntries().size(),
                    current.getSyncedAt(), null);
        }

        current.setEntries(result.entries());
        current.setEtag(result.etag());
        current.setSyncedAt(Instant.now());
        cache.save(current);

        log.info("Catalogue synced: {} plugin(s) available", result.entries().size());
        return new SyncResult(SyncResult.Outcome.UPDATED, result.entries().size(), current.getSyncedAt(),
                null);
    }

    /** @return the cached catalogue, which may be empty and may be stale */
    public List<CatalogRecords.CatalogEntry> entries() {
        return cached().getEntries();
    }

    /**
     * @param pluginId the plugin
     * @return its catalogue entry from the cache, or empty when the registry does not offer it
     */
    public Optional<CatalogRecords.CatalogEntry> entry(String pluginId) {
        return entries().stream().filter(entry -> entry.pluginId().equals(pluginId)).findFirst();
    }

    /** @return the cache document, including when it was last synced and why the last attempt failed */
    public CatalogCache cached() {
        return cache.findById(CatalogCache.ID).orElseGet(() -> {
            CatalogCache fresh = new CatalogCache();
            fresh.setId(CatalogCache.ID);
            return fresh;
        });
    }
}
