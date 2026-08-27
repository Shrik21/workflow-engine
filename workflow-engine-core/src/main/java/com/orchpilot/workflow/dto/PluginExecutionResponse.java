package com.orchpilot.workflow.dto;

import com.orchpilot.workflow.model.PluginExecutionRecord;

import java.time.Instant;
import java.util.Map;

/**
 * One recorded plugin invocation.
 *
 * <p>Request and response are the redacted, truncated copies the engine persisted. They are here because
 * "what did the plugin actually send" is the first question when an integration misbehaves.
 *
 * @param id             record id
 * @param executionId    execution it belongs to
 * @param workflowId     workflow it belongs to
 * @param nodeId         node that invoked the plugin
 * @param nodeType       node type
 * @param pluginId       plugin invoked
 * @param pluginVersion  version invoked
 * @param status         invocation outcome
 * @param attempt        attempt number
 * @param startTime      start time
 * @param endTime        end time
 * @param durationMillis duration
 * @param request        redacted request
 * @param response       redacted response
 * @param errorCode      failure code
 * @param errorMessage   failure message
 */
public record PluginExecutionResponse(String id, String executionId, String workflowId, String nodeId,
                                      String nodeType, String pluginId, String pluginVersion, String status,
                                      int attempt, Instant startTime, Instant endTime, long durationMillis,
                                      Map<String, Object> request, Map<String, Object> response,
                                      String errorCode, String errorMessage) {

    /**
     * @param record persistence model
     * @return the API representation
     */
    public static PluginExecutionResponse from(PluginExecutionRecord record) {
        return new PluginExecutionResponse(record.getId(), record.getExecutionId(), record.getWorkflowId(),
                record.getNodeId(), record.getNodeType(), record.getPluginId(), record.getPluginVersion(),
                record.getStatus(), record.getAttempt(), record.getStartTime(), record.getEndTime(),
                record.getDurationMillis(), record.getRequest(), record.getResponse(), record.getErrorCode(),
                record.getErrorMessage());
    }
}
