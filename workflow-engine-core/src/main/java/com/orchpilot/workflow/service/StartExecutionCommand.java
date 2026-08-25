package com.orchpilot.workflow.service;

import com.orchpilot.workflow.model.ExecutionMode;

import java.util.Map;

/**
 * One way to ask for an execution, used by all five execution modes.
 *
 * <p>A command object rather than five overloads: the REST endpoint, the cron poller, the event dispatcher and
 * the manual trigger differ only in the values they put here, which is what keeps a single code path for
 * starting work.
 *
 * @param workflowId      workflow to run
 * @param workflowVersion exact version, or {@code null} to use the published one
 * @param input           values exposed as {@code ${input.*}}
 * @param formData        submission for a form node reached immediately, so a fully specified run need not park
 * @param correlationId   caller-supplied id carried through logs and events
 * @param idempotencyKey  makes starting idempotent; a repeat returns the existing execution
 * @param mode            how the execution was started, for attribution
 * @param triggeredBy     who or what started it
 * @param triggerId       trigger that fired, when applicable
 * @param async           whether to return immediately and run on the engine's pool
 */
public record StartExecutionCommand(String workflowId, Integer workflowVersion, Map<String, Object> input,
                                    Map<String, Object> formData, String correlationId,
                                    String idempotencyKey, ExecutionMode mode, String triggeredBy,
                                    String triggerId, boolean async) {

    /**
     * @param workflowId workflow to run
     * @param input      execution input
     * @param mode       execution mode
     * @param actor      who started it
     * @param async      whether to run asynchronously
     * @return a command with no version pin, form data or idempotency key
     */
    public static StartExecutionCommand of(String workflowId, Map<String, Object> input, ExecutionMode mode,
                                           String actor, boolean async) {
        return new StartExecutionCommand(workflowId, null, input, null, null, null, mode, actor, null, async);
    }

    /** @return the input, never {@code null} */
    public Map<String, Object> safeInput() {
        return input == null ? Map.of() : input;
    }
}
