package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.model.ExecutionLogEntry;

import java.time.Instant;
import java.util.Map;

/**
 * One structured execution log line.
 *
 * @param sequence monotonic position within the execution
 * @param at       when it was written
 * @param level    severity
 * @param nodeId   node it relates to
 * @param nodeType node type
 * @param message  human-readable message
 * @param details  structured context
 */
public record ExecutionLogResponse(long sequence, Instant at, String level, String nodeId, String nodeType,
                                  String message, Map<String, Object> details) {

    /**
     * @param entry persistence model
     * @return the API representation
     */
    public static ExecutionLogResponse from(ExecutionLogEntry entry) {
        return new ExecutionLogResponse(entry.getSequence(), entry.getAt(), String.valueOf(entry.getLevel()),
                entry.getNodeId(), entry.getNodeType(), entry.getMessage(), entry.getDetails());
    }
}
