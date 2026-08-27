package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Access to the administrative audit trail.
 */
@Repository
public interface AuditRepository extends MongoRepository<AuditRecord, String> {

    Page<AuditRecord> findByEntityTypeAndEntityIdOrderByAtDesc(String entityType, String entityId, Pageable pageable);

    Page<AuditRecord> findByOrderByAtDesc(Pageable pageable);
}
