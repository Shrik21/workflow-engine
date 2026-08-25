package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.model.RetryPolicy;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.node.WorkflowNodeExecutor;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.node.NodeExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs one node under its retry policy.
 *
 * <p>Extracted from the engine so that "how many times do we try" is testable on its own and so the
 * engine loop stays about graph traversal.
 *
 * <p>Design decisions worth stating:
 *
 * <ul>
 *   <li><b>Only retryable failures are retried.</b> A configuration error or a 400 from an API will
 *       fail identically on the third attempt; retrying it just delays the inevitable and, for a node
 *       with side effects, risks partial duplicates. Plugins mark transient failures explicitly.</li>
 *   <li><b>WAITING is never retried.</b> Parking is a successful outcome, not a failure.</li>
 *   <li><b>Escaped exceptions become failure results.</b> A plugin that throws still gets its retry
 *       policy applied, and {@link PluginException} carries its own retryable flag.</li>
 *   <li><b>Backoff is interruptible.</b> A shutdown or cancellation during a backoff sleep abandons the
 *       retry rather than making the operator wait out a 60 second delay.</li>
 * </ul>
 */
@Component
public class NodeRetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(NodeRetryTemplate.class);

    /**
     * Executes a node, retrying according to its policy.
     *
     * @param executor executor for the node
     * @param node     node to execute
     * @param context  execution context
     * @return the final result; the last attempt's outcome when retries are exhausted
     */
    public NodeExecutionResult execute(WorkflowNodeExecutor executor, WorkflowNode node,
                                      WorkflowExecutionContext context) {
        RetryPolicy policy = node.effectiveRetry();
        int maxAttempts = policy.effectiveMaxAttempts();
        NodeExecutionResult result = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1) {
                long backoff = policy.backoffFor(attempt);
                if (!sleep(backoff, context, node)) {
                    return abandoned(node, result);
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("attempt", attempt);
                details.put("maxAttempts", maxAttempts);
                details.put("backoffMillis", backoff);
                details.put("previousError", result == null ? null : result.errorCode());
                context.logWarn(node.getId(), node.getType(), "Retrying node", details);
            }
            context.currentAttempt(attempt);
            result = attemptOnce(executor, node, context);

            if (result.status() != NodeExecutionStatus.FAILED) {
                return result;
            }
            if (!result.retryable()) {
                if (attempt > 1) {
                    log.debug("Node {} failed with non-retryable {} on attempt {}",
                            node.getId(), result.errorCode(), attempt);
                }
                return result;
            }
            if (attempt == maxAttempts) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("attempts", attempt);
                details.put("errorCode", result.errorCode());
                context.logError(node.getId(), node.getType(), "Retries exhausted", details);
            }
        }
        return result == null ? NodeExecutionResult.failure("NODE_NOT_EXECUTED", "No attempt was made") : result;
    }

    private NodeExecutionResult attemptOnce(WorkflowNodeExecutor executor, WorkflowNode node,
                                            WorkflowExecutionContext context) {
        try {
            NodeExecutionResult result = executor.execute(node, context);
            if (result == null) {
                return NodeExecutionResult.failure("NODE_RETURNED_NULL",
                        "Executor " + executor.getClass().getName() + " returned no result");
            }
            return result;
        } catch (PluginException ex) {
            log.warn("Node {} ({}) failed: {} - {}", node.getId(), node.getType(), ex.getErrorCode(),
                    ex.getMessage());
            return NodeExecutionResult.failure(ex.getErrorCode(), safeMessage(ex), ex.isRetryable());
        } catch (RuntimeException ex) {
            // An unexpected exception is a bug somewhere, but it must not take the engine down.
            log.error("Node {} ({}) threw {}", node.getId(), node.getType(),
                    ex.getClass().getSimpleName(), ex);
            return NodeExecutionResult.failure("NODE_EXECUTION_ERROR", safeMessage(ex), false);
        }
    }

    /**
     * @return {@code false} when the wait was interrupted or the execution was cancelled
     */
    private boolean sleep(long millis, WorkflowExecutionContext context, WorkflowNode node) {
        if (millis <= 0) {
            return !context.isCancelled();
        }
        long deadline = System.nanoTime() + millis * 1_000_000L;
        try {
            while (System.nanoTime() < deadline) {
                if (context.isCancelled()) {
                    log.debug("Abandoning retry backoff for node {}: execution cancelled", node.getId());
                    return false;
                }
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                Thread.sleep(Math.max(1, Math.min(250, remaining)));
            }
            return !context.isCancelled();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Retry backoff for node {} interrupted", node.getId());
            return false;
        }
    }

    private NodeExecutionResult abandoned(WorkflowNode node, NodeExecutionResult lastResult) {
        String previous = lastResult == null ? "none" : String.valueOf(lastResult.errorCode());
        return NodeExecutionResult.failure("RETRY_ABANDONED",
                "Retry of node '" + node.getId() + "' abandoned (previous error: " + previous + ")");
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1_000 ? message.substring(0, 1_000) + "..." : message;
    }
}
