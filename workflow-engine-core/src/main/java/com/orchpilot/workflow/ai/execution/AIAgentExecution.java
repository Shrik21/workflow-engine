package com.orchpilot.workflow.ai.execution;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A record of one AI Agent node execution, for history and observability.
 *
 * <p>Metadata only, by default: provider, model, timing, token counts, status and any error — never the prompt
 * or the response, which may hold sensitive data. Prompt/response logging is a separate, opt-in control for
 * customers who require it, so the default record is safe to keep and read widely.
 */
@Document(collection = "aiAgentExecutions")
public class AIAgentExecution {

    @Id
    private String id;

    @Indexed
    private String workflowExecutionId;
    private String nodeId;
    private String provider;
    private String model;
    private String status;
    private Instant startedAt;
    private Instant completedAt;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private int retryCount;
    private int toolCalls;
    private int blockedToolCalls;
    private int iterations;
    private int repairAttempts;
    private String stopReason;
    private String error;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public void setWorkflowExecutionId(String workflowExecutionId) {
        this.workflowExecutionId = workflowExecutionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
    }

    public int getBlockedToolCalls() {
        return blockedToolCalls;
    }

    public void setBlockedToolCalls(int blockedToolCalls) {
        this.blockedToolCalls = blockedToolCalls;
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public int getRepairAttempts() {
        return repairAttempts;
    }

    public void setRepairAttempts(int repairAttempts) {
        this.repairAttempts = repairAttempts;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
