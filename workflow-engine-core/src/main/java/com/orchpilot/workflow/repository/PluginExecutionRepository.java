package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.PluginExecutionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Access to plugin invocation history, which doubles as the idempotency ledger.
 */
@Repository
public interface PluginExecutionRepository extends MongoRepository<PluginExecutionRecord, String> {

    List<PluginExecutionRecord> findByExecutionIdOrderByStartTimeAsc(String executionId);

    Optional<PluginExecutionRecord> findByIdempotencyKey(String idempotencyKey);

    Page<PluginExecutionRecord> findByPluginIdOrderByStartTimeDesc(String pluginId, Pageable pageable);

    Page<PluginExecutionRecord> findByPluginIdAndPluginVersionOrderByStartTimeDesc(
            String pluginId, String pluginVersion, Pageable pageable);
}
