package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Access to plugin version documents.
 */
@Repository
public interface PluginVersionRepository extends MongoRepository<PluginVersion, String> {

    Optional<PluginVersion> findByPluginIdAndVersion(String pluginId, String version);

    List<PluginVersion> findByPluginIdOrderByUploadedAtDesc(String pluginId);

    List<PluginVersion> findByStatus(PluginStatus status);

    List<PluginVersion> findByStatusIn(List<PluginStatus> statuses);

    /**
     * @param nodeType node type contributed by a plugin
     * @return every version that contributes it, across plugins
     */
    List<PluginVersion> findByNodeTypesContaining(String nodeType);

    boolean existsByPluginIdAndVersion(String pluginId, String version);

    long countByPluginIdAndStatusNot(String pluginId, PluginStatus status);
}
