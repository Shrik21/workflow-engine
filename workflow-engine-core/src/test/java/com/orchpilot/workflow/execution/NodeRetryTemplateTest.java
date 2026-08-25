package com.orchpilot.workflow.execution;

import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.RetryPolicy;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.node.WorkflowNodeExecutor;
import com.orchpilot.workflow.sdk.exception.PluginExecutionException;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.support.TestContexts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRetryTemplateTest {

    private NodeRetryTemplate template;
    private WorkflowExecutionContext context;
    private TestContexts.RecordingLogWriter logWriter;

    @BeforeEach
    void setUp() {
        template = new NodeRetryTemplate();
        logWriter = new TestContexts.RecordingLogWriter();
        WorkflowNode node = TestContexts.node("n1", "TEST");
        context = TestContexts.context(TestContexts.version(List.of(node), List.of()), Map.of(), logWriter);
    }

    private static WorkflowNode nodeWithRetry(int maxAttempts) {
        WorkflowNode node = TestContexts.node("n1", "TEST");
        RetryPolicy policy = RetryPolicy.of(maxAttempts, 1);
        policy.setBackoffMultiplier(1.0);
        node.setRetry(policy);
        return node;
    }

    /** Executor that counts calls and returns a scripted sequence of results. */
    private static final class ScriptedExecutor implements WorkflowNodeExecutor {

        private final AtomicInteger calls = new AtomicInteger();
        private final List<NodeExecutionResult> script;

        private ScriptedExecutor(List<NodeExecutionResult> script) {
            this.script = script;
        }

        @Override
        public String getNodeType() {
            return "TEST";
        }

        @Override
        public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
            int index = calls.getAndIncrement();
            return script.get(Math.min(index, script.size() - 1));
        }

        int calls() {
            return calls.get();
        }
    }

    @Test
    @DisplayName("a success on the first attempt is not retried")
    void successIsNotRetried() {
        ScriptedExecutor executor = new ScriptedExecutor(List.of(NodeExecutionResult.success()));

        NodeExecutionResult result = template.execute(executor, nodeWithRetry(3), context);

        assertTrue(result.isSuccess());
        assertEquals(1, executor.calls());
    }

    @Test
    @DisplayName("a retryable failure is retried until it succeeds")
    void retryableFailureIsRetriedUntilSuccess() {
        ScriptedExecutor executor = new ScriptedExecutor(List.of(
                NodeExecutionResult.failure("TRANSIENT", "first", true),
                NodeExecutionResult.failure("TRANSIENT", "second", true),
                NodeExecutionResult.success(Map.of("ok", true))));

        NodeExecutionResult result = template.execute(executor, nodeWithRetry(3), context);

        assertTrue(result.isSuccess());
        assertEquals(3, executor.calls());
        assertTrue(logWriter.logged("Retrying node"));
    }

    @Test
    @DisplayName("a non-retryable failure is not retried, however many attempts are allowed")
    void nonRetryableFailureStopsImmediately() {
        ScriptedExecutor executor = new ScriptedExecutor(List.of(
                NodeExecutionResult.failure("BAD_REQUEST", "invalid address")));

        NodeExecutionResult result = template.execute(executor, nodeWithRetry(5), context);

        assertTrue(result.isFailed());
        assertEquals(1, executor.calls(),
                "retrying a permanent failure just delays it and burns the retry budget");
    }

    @Test
    @DisplayName("retries stop at the configured maximum")
    void retriesStopAtMaximum() {
        ScriptedExecutor executor = new ScriptedExecutor(List.of(
                NodeExecutionResult.failure("TRANSIENT", "still failing", true)));

        NodeExecutionResult result = template.execute(executor, nodeWithRetry(3), context);

        assertTrue(result.isFailed());
        assertEquals(3, executor.calls());
        assertTrue(logWriter.logged("Retries exhausted"));
    }

    @Test
    @DisplayName("a node with no retry policy runs exactly once")
    void noPolicyMeansSingleAttempt() {
        ScriptedExecutor executor = new ScriptedExecutor(List.of(
                NodeExecutionResult.failure("TRANSIENT", "failing", true)));

        template.execute(executor, TestContexts.node("n1", "TEST"), context);

        assertEquals(1, executor.calls(), "retrying must be opt-in");
    }

    @Test
    @DisplayName("waiting is never retried, because parking is a successful outcome")
    void waitingIsNotRetried() {
        ScriptedExecutor executor = new ScriptedExecutor(List.of(NodeExecutionResult.waiting("form")));

        NodeExecutionResult result = template.execute(executor, nodeWithRetry(3), context);

        assertTrue(result.isWaiting());
        assertEquals(1, executor.calls());
    }

    @Test
    @DisplayName("a thrown PluginException becomes a failure result and keeps its retryable flag")
    void thrownPluginExceptionKeepsRetryability() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowNodeExecutor throwing = new WorkflowNodeExecutor() {
            @Override
            public String getNodeType() {
                return "TEST";
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
                calls.incrementAndGet();
                throw new PluginExecutionException("API_TIMEOUT", "timed out", true);
            }
        };

        NodeExecutionResult result = template.execute(throwing, nodeWithRetry(2), context);

        assertTrue(result.isFailed());
        assertEquals("API_TIMEOUT", result.errorCode());
        assertEquals(2, calls.get(), "a thrown retryable exception must still be retried");
    }

    @Test
    @DisplayName("an unexpected runtime exception is contained rather than propagated")
    void unexpectedExceptionIsContained() {
        WorkflowNodeExecutor broken = new WorkflowNodeExecutor() {
            @Override
            public String getNodeType() {
                return "TEST";
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
                throw new IllegalStateException("bug in a plugin");
            }
        };

        NodeExecutionResult result = template.execute(broken, TestContexts.node("n1", "TEST"), context);

        assertTrue(result.isFailed());
        assertEquals("NODE_EXECUTION_ERROR", result.errorCode());
    }

    @Test
    @DisplayName("an executor returning null is reported instead of causing a NullPointerException later")
    void nullResultIsReported() {
        WorkflowNodeExecutor nullReturning = new WorkflowNodeExecutor() {
            @Override
            public String getNodeType() {
                return "TEST";
            }

            @Override
            public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
                return null;
            }
        };

        NodeExecutionResult result = template.execute(nullReturning, TestContexts.node("n1", "TEST"), context);

        assertEquals("NODE_RETURNED_NULL", result.errorCode());
    }

    @Test
    @DisplayName("cancellation abandons the backoff instead of making an operator wait it out")
    void cancellationAbandonsBackoff() {
        WorkflowNode node = TestContexts.node("n1", NodeTypes.PLUGIN);
        RetryPolicy policy = RetryPolicy.of(3, 30_000);
        node.setRetry(policy);
        ScriptedExecutor executor = new ScriptedExecutor(List.of(
                NodeExecutionResult.failure("TRANSIENT", "failing", true)));
        context.cancel();

        long start = System.currentTimeMillis();
        NodeExecutionResult result = template.execute(executor, node, context);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("RETRY_ABANDONED", result.errorCode());
        assertTrue(elapsed < 5_000, "a cancelled execution must not sit out a 30 second backoff");
    }

    @Test
    @DisplayName("exponential backoff grows and is capped")
    void backoffGrowsAndIsCapped() {
        RetryPolicy policy = RetryPolicy.of(5, 1_000);
        policy.setBackoffMultiplier(2.0);
        policy.setMaxBackoffMillis(3_000);

        assertEquals(0, policy.backoffFor(1));
        assertEquals(1_000, policy.backoffFor(2));
        assertEquals(2_000, policy.backoffFor(3));
        assertEquals(3_000, policy.backoffFor(4));
        assertEquals(3_000, policy.backoffFor(9));
        assertEquals(0, RetryPolicy.disabled().backoffFor(3));
        assertEquals(1, RetryPolicy.disabled().effectiveMaxAttempts());
    }
}
