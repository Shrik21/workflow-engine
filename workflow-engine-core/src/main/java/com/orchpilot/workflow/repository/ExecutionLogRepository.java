package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.ExecutionLogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to per-execution structured logs.
 */
@Repository
public interface ExecutionLogRepository extends MongoRepository<ExecutionLogEntry, String> {

    List<ExecutionLogEntry> findByExecutionIdOrderBySequenceAsc(String executionId, Pageable pageable);

    long countByExecutionId(String executionId);

    void deleteByExecutionId(String executionId);
}
