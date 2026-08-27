package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.model.ExecutionLogEntry;
import com.orchpilot.workflow.model.LogLevel;

import java.util.Map;

/**
 * Sink for per-execution structured log entries.
 *
 * <p>Behind an interface so that a deployment can send execution logs somewhere other than MongoDB
 * without the engine caring, and so unit tests can assert on what a node logged.
 */
public interface ExecutionLogWriter {

    /**
     * Writes one entry. Must never throw: a logging failure may not fail a workflow.
     *
     * @param entry entry to persist
     */
    void write(ExecutionLogEntry entry);

    /**
     * @param executionId execution the log belongs to
     * @return number of entries already recorded, used to continue the sequence after a resume
     */
    long countFor(String executionId);

    /**
     * Convenience factory so callers do not assemble the document by hand.
     *
     * @param executionId execution id
     * @param sequence    monotonic sequence within the execution
     * @param level       severity
     * @param nodeId      node the entry relates to, may be {@code null}
     * @param nodeType    node type, may be {@code null}
     * @param message     human-readable message
     * @param details     structured context, may be {@code null}
     * @return a populated entry
     */
    static ExecutionLogEntry entry(String executionId, long sequence, LogLevel level, String nodeId,
                                   String nodeType, String message, Map<String, Object> details) {
        ExecutionLogEntry entry = new ExecutionLogEntry();
        entry.setExecutionId(executionId);
        entry.setSequence(sequence);
        entry.setAt(java.time.Instant.now());
        entry.setLevel(level);
        entry.setNodeId(nodeId);
        entry.setNodeType(nodeType);
        entry.setMessage(message);
        entry.setDetails(details);
        return entry;
    }
}
