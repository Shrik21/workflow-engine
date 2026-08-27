package com.orchpilot.workflow.execution;

import java.util.Map;

/**
 * Data that satisfies a parked node, supplied when an execution is resumed.
 *
 * @param nodeId node the data is for; must match the node that parked the execution
 * @param data   submitted values, exposed to the node as its signal payload
 */
public record ResumeSignal(String nodeId, Map<String, Object> data) {

    /**
     * @param nodeId node the data is for
     * @param data   submitted values, may be {@code null}
     * @return a signal with an immutable payload
     */
    public static ResumeSignal of(String nodeId, Map<String, Object> data) {
        return new ResumeSignal(nodeId, data == null ? Map.of() : Map.copyOf(data));
    }
}
