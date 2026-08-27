package com.orchpilot.workflow.node;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;

/**
 * The one contract the execution engine uses to run anything.
 *
 * <p>Built-in nodes implement it directly. Plugin nodes reach it through a single adapter, so the
 * engine has exactly one code path for both and no knowledge of what any particular node does.
 *
 * <p>Implementations must be thread-safe and stateless: one instance serves every concurrent
 * execution.
 */
public interface WorkflowNodeExecutor {

    /**
     * @return the node type this executor handles, e.g. {@code DECISION}
     */
    String getNodeType();

    /**
     * Executes one node.
     *
     * <p>Implementations should return a failure result rather than throwing, so the engine can apply
     * the node's retry and error policy. The engine converts an escaped exception into a failure
     * result, but that loses the chance to mark it retryable.
     *
     * @param node    the node definition, taken from the pinned workflow version
     * @param context live execution state: variables, expression evaluation, logging, cancellation
     * @return the outcome; never {@code null}
     */
    NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context);

    /**
     * Whether reaching this node ends the workflow.
     *
     * <p>Expressed as a capability of the executor rather than a check on the node type, so the engine
     * never has to ask "is this an END node". A future plugin can contribute its own terminal node type
     * without the engine changing.
     *
     * @return {@code true} when a successful execution of this node completes the workflow
     */
    default boolean isTerminal() {
        return false;
    }
}
