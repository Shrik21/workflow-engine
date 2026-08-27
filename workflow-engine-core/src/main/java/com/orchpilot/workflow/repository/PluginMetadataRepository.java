package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.PluginMetadata;
import com.orchpilot.workflow.model.PluginStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to plugin heads.
 */
@Repository
public interface PluginMetadataRepository extends MongoRepository<PluginMetadata, String> {

    List<PluginMetadata> findByStatus(PluginStatus status);

    List<PluginMetadata> findByStatusNot(PluginStatus status);
}
