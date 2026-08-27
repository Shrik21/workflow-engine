package com.orchpilot.workflow.sdk.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of one node execution attempt, returned by both built-in node executors and
 * plugin nodes.
 *
 * <p>This is the single value type the engine understands. A plugin never throws to signal an
 * expected business failure; it returns {@link #failure(String, String)} so the engine can apply
 * the node's retry and error policy consistently.
 *
 * <p>Instances are safe to share between threads. {@link #outputs()} is always an unmodifiable map.
 *
 * @since 1.0.0
 */
public final class NodeExecutionResult {

    private final NodeExecutionStatus status;
    private final Map<String, Object> outputs;
    private final String selectedBranch;
    private final String errorCode;
    private final String errorMessage;
    private final boolean retryable;
    private final String waitReason;

    private NodeExecutionResult(Builder builder) {
        this.status = Objects.requireNonNull(builder.status, "status");
        this.outputs = builder.outputs.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.outputs));
        this.selectedBranch = builder.selectedBranch;
        this.errorCode = builder.errorCode;
        this.errorMessage = builder.errorMessage;
        this.retryable = builder.retryable;
        this.waitReason = builder.waitReason;
    }

    /**
     * @return a successful result with no outputs
     */
    public static NodeExecutionResult success() {
        return builder(NodeExecutionStatus.SUCCESS).build();
    }

    /**
     * @param outputs values to publish into the node's output scope; {@code null} is treated as empty
     * @return a successful result carrying outputs
     */
    public static NodeExecutionResult success(Map<String, Object> outputs) {
        return builder(NodeExecutionStatus.SUCCESS).outputs(outputs).build();
    }

    /**
     * Successful result that also tells the engine which outgoing branch to follow. Used by
     * decision-style nodes.
     *
     * @param branch  value matched against the {@code sourcePort} of outgoing connections
     * @param outputs values to publish into the node's output scope
     * @return a successful, branch-selecting result
     */
    public static NodeExecutionResult branch(String branch, Map<String, Object> outputs) {
        return builder(NodeExecutionStatus.SUCCESS).selectedBranch(branch).outputs(outputs).build();
    }

    /**
     * @param errorCode stable, machine-readable failure identifier, e.g. {@code API_TIMEOUT}
     * @param message   human-readable description; must never contain secrets
     * @return a non-retryable failure
     */
    public static NodeExecutionResult failure(String errorCode, String message) {
        return builder(NodeExecutionStatus.FAILED).error(errorCode, message).build();
    }

    /**
     * @param errorCode stable, machine-readable failure identifier
     * @param message   human-readable description; must never contain secrets
     * @param retryable whether an identical later attempt could succeed
     * @return a failure result
     */
    public static NodeExecutionResult failure(String errorCode, String message, boolean retryable) {
        return builder(NodeExecutionStatus.FAILED).error(errorCode, message).retryable(retryable).build();
    }

    /**
     * @param reason why the execution is parked, shown in execution logs
     * @return a result that parks the execution until it is resumed
     */
    public static NodeExecutionResult waiting(String reason) {
        return builder(NodeExecutionStatus.WAITING).waitReason(reason).build();
    }

    /**
     * @param reason  why the execution is parked
     * @param outputs values to publish before parking, e.g. the pending form descriptor
     * @return a result that parks the execution until it is resumed
     */
    public static NodeExecutionResult waiting(String reason, Map<String, Object> outputs) {
        return builder(NodeExecutionStatus.WAITING).waitReason(reason).outputs(outputs).build();
    }

    /**
     * @param reason why the node chose to do nothing
     * @return a skipped result
     */
    public static NodeExecutionResult skipped(String reason) {
        return builder(NodeExecutionStatus.SKIPPED).waitReason(reason).build();
    }

    /**
     * @param status the status the built result will carry
     * @return a new builder
     */
    public static Builder builder(NodeExecutionStatus status) {
        return new Builder(status);
    }

    /** @return the attempt outcome */
    public NodeExecutionStatus status() {
        return status;
    }

    /** @return unmodifiable node outputs, never {@code null} */
    public Map<String, Object> outputs() {
        return outputs;
    }

    /** @return the branch to follow, or {@code null} when the engine should use the default edge */
    public String selectedBranch() {
        return selectedBranch;
    }

    /** @return stable failure identifier, or {@code null} when not failed */
    public String errorCode() {
        return errorCode;
    }

    /** @return human-readable failure description, or {@code null} when not failed */
    public String errorMessage() {
        return errorMessage;
    }

    /** @return whether an identical later attempt could succeed */
    public boolean retryable() {
        return retryable;
    }

    /** @return why the node is waiting or was skipped, or {@code null} */
    public String waitReason() {
        return waitReason;
    }

    /** @return {@code true} when {@link #status()} is {@link NodeExecutionStatus#SUCCESS} */
    public boolean isSuccess() {
        return status == NodeExecutionStatus.SUCCESS;
    }

    /** @return {@code true} when {@link #status()} is {@link NodeExecutionStatus#FAILED} */
    public boolean isFailed() {
        return status == NodeExecutionStatus.FAILED;
    }

    /** @return {@code true} when {@link #status()} is {@link NodeExecutionStatus#WAITING} */
    public boolean isWaiting() {
        return status == NodeExecutionStatus.WAITING;
    }

    /**
     * @param extra additional outputs, merged over the existing ones
     * @return a copy of this result with {@code extra} merged into its outputs
     */
    public NodeExecutionResult withOutputs(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(this.outputs);
        merged.putAll(extra);
        return builder(status)
                .outputs(merged)
                .selectedBranch(selectedBranch)
                .error(errorCode, errorMessage)
                .retryable(retryable)
                .waitReason(waitReason)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NodeExecutionResult)) {
            return false;
        }
        NodeExecutionResult other = (NodeExecutionResult) o;
        return status == other.status
                && retryable == other.retryable
                && outputs.equals(other.outputs)
                && Objects.equals(selectedBranch, other.selectedBranch)
                && Objects.equals(errorCode, other.errorCode)
                && Objects.equals(errorMessage, other.errorMessage)
                && Objects.equals(waitReason, other.waitReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, outputs, selectedBranch, errorCode, errorMessage, retryable, waitReason);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NodeExecutionResult{status=").append(status);
        if (selectedBranch != null) {
            sb.append(", branch=").append(selectedBranch);
        }
        if (errorCode != null) {
            sb.append(", errorCode=").append(errorCode).append(", retryable=").append(retryable);
        }
        if (!outputs.isEmpty()) {
            sb.append(", outputKeys=").append(outputs.keySet());
        }
        return sb.append('}').toString();
    }

    /**
     * Mutable builder for {@link NodeExecutionResult}. Not thread-safe; the built result is.
     *
     * @since 1.0.0
     */
    public static final class Builder {

        private final NodeExecutionStatus status;
        private final Map<String, Object> outputs = new LinkedHashMap<>();
        private String selectedBranch;
        private String errorCode;
        private String errorMessage;
        private boolean retryable;
        private String waitReason;

        private Builder(NodeExecutionStatus status) {
            this.status = status;
        }

        /**
         * @param key   output name
         * @param value output value, may be {@code null}
         * @return this builder
         */
        public Builder output(String key, Object value) {
            if (key != null) {
                this.outputs.put(key, value);
            }
            return this;
        }

        /**
         * @param values outputs to add; {@code null} is ignored
         * @return this builder
         */
        public Builder outputs(Map<String, Object> values) {
            if (values != null) {
                this.outputs.putAll(values);
            }
            return this;
        }

        /**
         * @param branch outgoing branch name
         * @return this builder
         */
        public Builder selectedBranch(String branch) {
            this.selectedBranch = branch;
            return this;
        }

        /**
         * @param code    stable failure identifier
         * @param message human-readable description
         * @return this builder
         */
        public Builder error(String code, String message) {
            this.errorCode = code;
            this.errorMessage = message;
            return this;
        }

        /**
         * @param value whether an identical later attempt could succeed
         * @return this builder
         */
        public Builder retryable(boolean value) {
            this.retryable = value;
            return this;
        }

        /**
         * @param reason why the node is waiting or was skipped
         * @return this builder
         */
        public Builder waitReason(String reason) {
            this.waitReason = reason;
            return this;
        }

        /**
         * @return an immutable result
         */
        public NodeExecutionResult build() {
            return new NodeExecutionResult(this);
        }
    }
}
