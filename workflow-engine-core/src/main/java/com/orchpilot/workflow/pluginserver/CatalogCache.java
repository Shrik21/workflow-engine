package com.orchpilot.workflow.pluginserver;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The last catalogue this engine successfully fetched.
 *
 * <p>A single document, id {@code current}. Persisted rather than held in memory for one requirement: the engine
 * must remain usable when the registry is down, including across a restart of the engine itself. An in-memory cache
 * satisfies the first half and fails the second, which is the half that matters during an incident where both
 * services are being restarted.
 *
 * <p>{@code syncedAt} is shown to the user next to the marketplace, because a stale catalogue presented as current
 * is worse than an honest "last synchronised 40 minutes ago".
 */
@Document(collection = "plugin_catalog_cache")
public class CatalogCache {

    /** There is only ever one of these. */
    public static final String ID = "current";

    @Id
    private String id = ID;

    private List<CatalogRecords.CatalogEntry> entries = new ArrayList<>();

    /** The registry's validator, sent back as {@code If-None-Match} so a steady state costs a 304. */
    private String etag;

    private Instant syncedAt;

    /** Why the last attempt failed, or null when the last attempt succeeded. */
    private String lastError;

    private Instant lastAttemptAt;

    private int syncCount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<CatalogRecords.CatalogEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<CatalogRecords.CatalogEntry> entries) {
        this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(Instant syncedAt) {
        this.syncedAt = syncedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public int getSyncCount() {
        return syncCount;
    }

    public void setSyncCount(int syncCount) {
        this.syncCount = syncCount;
    }

    /** @return whether this engine has ever successfully read the catalogue */
    public boolean hasEverSynced() {
        return syncedAt != null;
    }

    /**
     * @param staleAfter how old a catalogue may be before it is worth saying so
     * @return whether the cached catalogue is older than that
     */
    public boolean isStale(Duration staleAfter) {
        return syncedAt == null || syncedAt.isBefore(Instant.now().minus(staleAfter));
    }

    /** @return how old the cached catalogue is, or null when there has never been one */
    public Duration age() {
        return syncedAt == null ? null : Duration.between(syncedAt, Instant.now());
    }
}
