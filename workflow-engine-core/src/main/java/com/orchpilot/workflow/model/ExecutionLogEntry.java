package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only structured log line for one execution.
 *
 * <p>Separate from the execution document so that a chatty run cannot grow the execution past
 * MongoDB's document size limit, and so that log retention can be managed independently of execution
 * retention.
 */
@Document(collection = "workflow_execution_logs")
@CompoundIndex(name = "log_execution_sequence", def = "{'executionId': 1, 'sequence': 1}")
public class ExecutionLogEntry {

    @Id
    private String id;

    private String executionId;

    /** Monotonic within an execution, so entries written in the same millisecond stay ordered. */
    private long sequence;

    private Instant at;
    private LogLevel level = LogLevel.INFO;
    private String nodeId;
    private String nodeType;
    private String message;

    /** Structured context. Secrets are redacted before persistence. */
    private Map<String, Object> details = new LinkedHashMap<>();

    public ExecutionLogEntry() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public LogLevel getLevel() {
        return level;
    }

    public void setLevel(LogLevel level) {
        this.level = level;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details == null ? new LinkedHashMap<>() : details;
    }
}
