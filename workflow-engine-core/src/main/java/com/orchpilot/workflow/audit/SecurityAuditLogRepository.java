package com.orchpilot.workflow.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Security audit trail. Append-only in practice: nothing in the application updates a record. */
public interface SecurityAuditLogRepository extends MongoRepository<SecurityAuditLog, String> {

    Page<SecurityAuditLog> findByUserIdOrderByAtDesc(String userId, Pageable pageable);

    Page<SecurityAuditLog> findByEventOrderByAtDesc(SecurityAuditEvent event, Pageable pageable);

    Page<SecurityAuditLog> findAllByOrderByAtDesc(Pageable pageable);
}
