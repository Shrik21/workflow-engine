package com.orchpilot.pluginserver.repository;

import com.orchpilot.pluginserver.model.PluginAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** The append-only registry trail. */
public interface PluginAuditRepository extends MongoRepository<PluginAuditEvent, String> {

    Page<PluginAuditEvent> findByPluginIdOrderByAtDesc(String pluginId, Pageable pageable);

    Page<PluginAuditEvent> findAllByOrderByAtDesc(Pageable pageable);
}
