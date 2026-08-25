package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.model.ExecutionLogEntry;
import com.orchpilot.workflow.repository.ExecutionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persists execution logs to MongoDB, and swallows its own failures.
 *
 * <p>Deliberate: if the log collection is full, unavailable or misconfigured, workflows must keep
 * running. The failure is reported to the application log, where operational monitoring will see it.
 */
@Component
public class MongoExecutionLogWriter implements ExecutionLogWriter {

    private static final Logger log = LoggerFactory.getLogger(MongoExecutionLogWriter.class);

    private final ExecutionLogRepository repository;

    public MongoExecutionLogWriter(ExecutionLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void write(ExecutionLogEntry entry) {
        try {
            repository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Could not persist execution log entry for {} (seq {}): {}",
                    entry.getExecutionId(), entry.getSequence(), ex.getMessage());
        }
    }

    @Override
    public long countFor(String executionId) {
        try {
            return repository.countByExecutionId(executionId);
        } catch (RuntimeException ex) {
            log.warn("Could not count execution log entries for {}: {}", executionId, ex.getMessage());
            return 0;
        }
    }
}
