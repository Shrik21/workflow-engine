package com.orchpilot.workflow.support;

import com.orchpilot.workflow.execution.ExecutionLogWriter;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.expression.SpelExpressionEvaluator;
import com.orchpilot.workflow.model.ExecutionMode;
import com.orchpilot.workflow.model.ExecutionLogEntry;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowExecution;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.variable.DefaultVariableResolver;
import com.orchpilot.workflow.variable.VariableMapper;
import com.orchpilot.workflow.variable.VariableStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builders for the objects node executor tests need.
 *
 * <p>Real collaborators rather than mocks for the resolver, mapper and expression evaluator: those three are pure and
 * fast, and mocking them would mean a test could pass while variable resolution was broken, which is the thing most
 * worth catching.
 */
public final class TestContexts {

    private TestContexts() {
    }

    /** Log writer that keeps entries in memory so tests can assert on what a node logged. */
    public static final class RecordingLogWriter implements ExecutionLogWriter {

        private final List<ExecutionLogEntry> entries = new ArrayList<>();

        @Override
        public void write(ExecutionLogEntry entry) {
            entries.add(entry);
        }

        @Override
        public long countFor(String executionId) {
            return entries.size();
        }

        /** @return every entry written, in order */
        public List<ExecutionLogEntry> entries() {
            return entries;
        }

        /**
         * @param fragment text to look for
         * @return whether any entry's message contains it
         */
        public boolean logged(String fragment) {
            return entries.stream().anyMatch(entry -> entry.getMessage() != null
                    && entry.getMessage().contains(fragment));
        }
    }

    /**
     * @param nodes       nodes of the workflow under test
     * @param connections edges of the workflow under test
     * @return a published version wrapping them
     */
    public static WorkflowVersion version(List<WorkflowNode> nodes, List<WorkflowConnection> connections) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(WorkflowVersion.idFor("wf-test", 1));
        version.setWorkflowId("wf-test");
        version.setVersion(1);
        version.setName("Test Workflow");
        version.setNodes(new ArrayList<>(nodes));
        version.setConnections(new ArrayList<>(connections));
        version.setPublishedAt(Instant.now());
        return version;
    }

    /**
     * @param version definition the execution runs against
     * @return a fresh execution document in PENDING
     */
    public static WorkflowExecution execution(WorkflowVersion version) {
        WorkflowExecution execution = new WorkflowExecution();
        execution.setId("exec-test-1");
        execution.setWorkflowId(version.getWorkflowId());
        execution.setWorkflowVersion(version.getVersion());
        execution.setWorkflowName(version.getName());
        execution.setMode(ExecutionMode.SYNCHRONOUS);
        execution.setCreatedAt(Instant.now());
        execution.setStartedAt(Instant.now());
        return execution;
    }

    /**
     * @param version   definition
     * @param variables values to seed into the workflow scope
     * @param logWriter log sink
     * @return a ready-to-use execution context
     */
    public static WorkflowExecutionContext context(WorkflowVersion version, Map<String, Object> variables,
                                                   RecordingLogWriter logWriter) {
        VariableStore store = VariableStore.create();
        if (variables != null) {
            store.seed(com.orchpilot.workflow.variable.VariableScope.WORKFLOW, variables);
        }
        DefaultVariableResolver resolver = new DefaultVariableResolver();
        return new WorkflowExecutionContext(execution(version), version, store, resolver,
                new VariableMapper(resolver), new SpelExpressionEvaluator(), logWriter, 0);
    }

    /**
     * @param id   node id
     * @param type node type
     * @return a minimally configured node
     */
    public static WorkflowNode node(String id, String type) {
        return new WorkflowNode(id, type, id);
    }
}
