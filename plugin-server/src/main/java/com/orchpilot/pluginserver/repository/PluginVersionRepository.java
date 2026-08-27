package com.orchpilot.pluginserver.repository;

import com.orchpilot.pluginserver.model.PluginStatus;
import com.orchpilot.pluginserver.model.PluginVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Plugin versions, keyed by {@code pluginId:version}.
 *
 * <p>Ordering is by the derived {@code order} fields rather than by the version text, because sorting
 * {@code "1.10.0"} against {@code "1.9.0"} as a string gives the wrong answer. Covered by
 * {@code ix_plugin_precedence}, so resolving a plugin's latest version is an index scan rather than a load of
 * every version into the application.
 */
public interface PluginVersionRepository extends MongoRepository<PluginVersion, String> {

    List<PluginVersion> findByPluginIdOrderByOrderMajorDescOrderMinorDescOrderPatchDescOrderReleaseRankDesc(
            String pluginId);

    Optional<PluginVersion> findByPluginIdAndVersion(String pluginId, String version);

    List<PluginVersion> findByPluginIdAndStatusIn(String pluginId, Collection<PluginStatus> statuses);

    /**
     * The newest version in a given set of states.
     *
     * <p>Used to recompute a plugin's latest version. Restricting the states at the query rather than filtering
     * afterwards is what keeps a deprecated or revoked version from being promoted to latest by accident.
     */
    Optional<PluginVersion>
            findFirstByPluginIdAndStatusInOrderByOrderMajorDescOrderMinorDescOrderPatchDescOrderReleaseRankDesc(
            String pluginId, Collection<PluginStatus> statuses);

    List<PluginVersion> findByStatusIn(Collection<PluginStatus> statuses);

    long countByPluginId(String pluginId);

    /** Detects the same bytes uploaded again under a different version number. */
    List<PluginVersion> findByChecksum(String checksum);

    boolean existsByPluginIdAndVersion(String pluginId, String version);

    void deleteByPluginId(String pluginId);
}
