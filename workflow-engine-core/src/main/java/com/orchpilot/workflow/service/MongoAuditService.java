package com.orchpilot.workflow.service;

import com.orchpilot.workflow.model.AuditRecord;
import com.orchpilot.workflow.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Writes the audit trail to MongoDB and mirrors it to the application log, so that the record survives
 * even if the collection does not.
 */
@Service
public class MongoAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(MongoAuditService.class);

    private final AuditRepository repository;

    public MongoAuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(String actor, String action, String entityType, String entityId, String outcome,
                       Map<String, Object> details) {
        log.info("AUDIT actor={} action={} entity={}:{} outcome={}",
                actor == null ? "unknown" : actor, action, entityType, entityId, outcome);
        try {
            AuditRecord record = new AuditRecord();
            record.setAt(Instant.now());
            record.setActor(actor == null ? "unknown" : actor);
            record.setAction(action);
            record.setEntityType(entityType);
            record.setEntityId(entityId);
            record.setOutcome(outcome);
            record.setDetails(details);
            repository.save(record);
        } catch (RuntimeException ex) {
            log.warn("Could not persist audit record for {} on {}:{}: {}", action, entityType, entityId,
                    ex.getMessage());
        }
    }

    @Override
    public Page<AuditRecord> history(String entityType, String entityId, Pageable pageable) {
        return repository.findByEntityTypeAndEntityIdOrderByAtDesc(entityType, entityId, pageable);
    }
}
