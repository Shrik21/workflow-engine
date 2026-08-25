package com.orchpilot.pluginserver.repository;

import com.orchpilot.pluginserver.model.Plugin;
import com.orchpilot.pluginserver.model.PluginStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Collection;
import java.util.List;

/**
 * Plugin heads, keyed by plugin id.
 *
 * <p>A top-level interface, like every other repository in this platform. Spring Data does not create proxies for
 * interfaces nested inside a container class, and grouping them to save a file produces a context that fails to
 * start with "required a bean of type ... that could not be found".
 */
public interface PluginRepository extends MongoRepository<Plugin, String> {

    Page<Plugin> findByStatusIn(Collection<PluginStatus> statuses, Pageable pageable);

    List<Plugin> findByStatusInOrderByPluginIdAsc(Collection<PluginStatus> statuses);

    /**
     * The catalogue's plugin set.
     *
     * <p>Filtered by the plugin's own status only. Whether it has anything installable is decided downstream by
     * {@code PluginCatalogEntry.of}, which returns null when no <em>published</em> version backs the head, and
     * that is the condition actually meant: a plugin whose versions are all drafts has no published version and
     * is correctly absent.
     *
     * <p>This deliberately does not also require a non-null {@code latestVersion}. A plugin whose only version
     * has been deprecated has no latest, because latest excludes deprecated releases, and excluding it here made
     * it vanish from the catalogue entirely rather than appear as deprecated. A workflow service pinned to that
     * version is still running it and still needs to be told it is deprecated, which is precisely the case the
     * fallback in {@code PluginCatalogEntry.of} was written for and could never reach.
     */
    @Query("{ 'status': { $in: ?0 } }")
    List<Plugin> findCatalogueEntries(Collection<PluginStatus> statuses);

    /** Case-insensitive search across id, name and vendor, for the admin list. */
    @Query("{ $or: [ { '_id': { $regex: ?0, $options: 'i' } }, "
            + "{ 'name': { $regex: ?0, $options: 'i' } }, "
            + "{ 'vendor': { $regex: ?0, $options: 'i' } } ] }")
    Page<Plugin> search(String term, Pageable pageable);
}
