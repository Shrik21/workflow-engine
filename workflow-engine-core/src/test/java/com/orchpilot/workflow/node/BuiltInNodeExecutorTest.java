package com.orchpilot.workflow.node;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.model.DecisionCondition;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.support.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the four node types the engine implements itself.
 */
class BuiltInNodeExecutorTest {

    private static WorkflowExecutionContext contextWith(WorkflowNode node, Map<String, Object> variables,
                                                        TestContexts.RecordingLogWriter logWriter) {
        WorkflowVersion version = TestContexts.version(List.of(node), List.of());
        return TestContexts.context(version, variables, logWriter);
    }

    @Nested
    @DisplayName("Start node")
    class StartNode {

        private final StartNodeExecutor executor = new StartNodeExecutor();

        @Test
        @DisplayName("declares the START type")
        void declaresType() {
            assertEquals(NodeTypes.START, executor.getNodeType());
            assertFalse(executor.isTerminal());
        }

        @Test
        @DisplayName("applies declared defaults without overwriting caller-supplied values")
        void defaultsDoNotOverwriteCallerValues() {
            WorkflowNode node = TestContexts.node("start-1", NodeTypes.START);
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("region", "EU");
            defaults.put("currency", "EUR");
            node.setConfiguration(Map.of("variables", defaults));

            WorkflowExecutionContext context = contextWith(node, Map.of("region", "APAC"),
                    new TestContexts.RecordingLogWriter());
            NodeExecutionResult result = executor.execute(node, context);

            assertTrue(result.isSuccess());
            assertEquals("APAC", context.variables().find("workflow.region").orElse(null),
                    "a default must not clobber a value the caller supplied");
            assertEquals("EUR", context.variables().find("workflow.currency").orElse(null));
        }

        @Test
        @DisplayName("promotes mapped input into workflow variables")
        void appliesInputMapping() {
            WorkflowNode node = TestContexts.node("start-1", NodeTypes.START);
            node.setInputMapping(Map.of("employeeId", "${input.employeeId}"));

            WorkflowExecutionContext context = contextWith(node, Map.of(),
                    new TestContexts.RecordingLogWriter());
            context.variables().seed(com.orchpilot.workflow.variable.VariableScope.INPUT,
                    Map.of("employeeId", "E-42"));

            executor.execute(node, context);

            assertEquals("E-42", context.variables().find("workflow.employeeId").orElse(null));
        }
    }

    @Nested
    @DisplayName("Form node")
    class FormNode {

        /**
         * The node executor with its collaborators stubbed.
         *
         * <p>{@code binding} returns no form, which is the "references no published form" path: the node still
         * raises a task and still hands the raw submission to the output mapping, which is what every workflow
         * built before the form designer existed relies on. The form-backed path is covered by
         * {@link com.orchpilot.workflow.task.HumanTaskEngineTest}, which needs a real form version to be worth
         * anything.
         */
        private final FormNodeBinding binding = org.mockito.Mockito.mock(FormNodeBinding.class);
        private final com.orchpilot.workflow.task.HumanTaskService taskService =
                org.mockito.Mockito.mock(com.orchpilot.workflow.task.HumanTaskService.class);
        private final com.orchpilot.workflow.task.TaskAssignmentResolver assignments =
                org.mockito.Mockito.mock(com.orchpilot.workflow.task.TaskAssignmentResolver.class);

        private final FormNodeExecutor executor = new FormNodeExecutor(binding,
                new com.orchpilot.workflow.forms.DefaultFormVariableMapper(), taskService, assignments);

        @org.junit.jupiter.api.BeforeEach
        void stubCollaborators() {
            org.mockito.Mockito.when(binding.formIdOf(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> {
                        WorkflowNode node = call.getArgument(0);
                        return node.getFormId() == null ? node.getId() : node.getFormId();
                    });
            org.mockito.Mockito.when(binding.resolve(org.mockito.ArgumentMatchers.any(WorkflowNode.class),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.Optional.empty());
            org.mockito.Mockito.when(assignments.resolve(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new com.orchpilot.workflow.task.TaskAssignment(null, null, List.of(),
                            List.of("finance"), com.orchpilot.workflow.task.TaskPriority.NORMAL, null, null,
                            List.of(), false));
            com.orchpilot.workflow.task.HumanTask raised = new com.orchpilot.workflow.task.HumanTask();
            raised.setId("task-1");
            org.mockito.Mockito.when(taskService.createOrReuse(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(raised);
        }

        @Test
        @DisplayName("parks the execution and raises a task when no submission is available")
        void parksWhenNoSubmission() {
            WorkflowNode node = TestContexts.node("form-1", NodeTypes.FORM);
            node.setFormId("employeeApproval");
            node.setInputMapping(Map.of("employeeId", "${employeeId}"));

            TestContexts.RecordingLogWriter logWriter = new TestContexts.RecordingLogWriter();
            WorkflowExecutionContext context = contextWith(node, Map.of("employeeId", "E-42"), logWriter);
            NodeExecutionResult result = executor.execute(node, context);

            assertTrue(result.isWaiting());
            assertEquals("employeeApproval", result.outputs().get("formId"));
            assertEquals("task-1", result.outputs().get("taskId"),
                    "the parked signal must name the task, or the inbox cannot find the wait");
            @SuppressWarnings("unchecked")
            Map<String, Object> prefill = (Map<String, Object>) result.outputs().get("prefill");
            assertEquals("E-42", prefill.get("employeeId"), "the client needs prefilled values to render the form");
            assertTrue(logWriter.logged("Waiting for a person to complete task task-1"));
        }

        @Test
        @DisplayName("raises exactly one task however often the node is re-entered")
        void raisesOneTaskPerWait() {
            WorkflowNode node = TestContexts.node("form-1", NodeTypes.FORM);
            node.setFormId("employeeApproval");
            WorkflowExecutionContext context = contextWith(node, Map.of(),
                    new TestContexts.RecordingLogWriter());

            executor.execute(node, context);
            executor.execute(node, context);

            /*
             * Twice, because the executor genuinely asks twice: a retry, a resume after a crash and another
             * instance picking the execution up all re-enter the node. Idempotency is the service's job, keyed
             * on execution and node, and that is what this asserts the executor relies on rather than
             * second-guessing.
             */
            org.mockito.Mockito.verify(taskService, org.mockito.Mockito.times(2))
                    .createOrReuse(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("completes immediately when the submission is already present")
        void completesWithSubmission() {
            WorkflowNode node = TestContexts.node("form-1", NodeTypes.FORM);
            node.setFormId("employeeApproval");

            WorkflowExecutionContext context = contextWith(node, Map.of(),
                    new TestContexts.RecordingLogWriter());
            context.offerSignal("form-1", Map.of("approved", true, "comments", "looks fine"));

            NodeExecutionResult result = executor.execute(node, context);

            assertTrue(result.isSuccess());
            assertEquals(true, result.outputs().get("approved"));
            assertEquals("looks fine", result.outputs().get("comments"));
            assertEquals(true, result.outputs().get("submitted"));
        }

        @Test
        @DisplayName("consumes the submission once, so a loop back to the node parks again")
        void consumesSubmissionOnce() {
            WorkflowNode node = TestContexts.node("form-1", NodeTypes.FORM);
            node.setFormId("f");
            WorkflowExecutionContext context = contextWith(node, Map.of(),
                    new TestContexts.RecordingLogWriter());
            context.offerSignal("form-1", Map.of("approved", true));

            assertTrue(executor.execute(node, context).isSuccess());
            assertTrue(executor.execute(node, context).isWaiting(),
                    "a second pass must not silently reuse the first submission");
        }

        @Test
        @DisplayName("fails rather than hanging when waiting is disabled")
        void failsWhenWaitingDisabled() {
            WorkflowNode node = TestContexts.node("form-1", NodeTypes.FORM);
            node.setFormId("f");
            node.setWaitForInput(false);

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of(), new TestContexts.RecordingLogWriter()));

            assertTrue(result.isFailed());
            assertEquals("FORM_INPUT_REQUIRED", result.errorCode());
        }
    }

    @Nested
    @DisplayName("Decision node")
    class DecisionNode {

        private final DecisionNodeExecutor executor = new DecisionNodeExecutor();

        private WorkflowNode decisionNode() {
            WorkflowNode node = TestContexts.node("decision-1", NodeTypes.DECISION);
            node.setConditions(List.of(
                    new DecisionCondition("approved", "amount > 10000"),
                    new DecisionCondition("normal", "amount <= 10000")));
            return node;
        }

        @Test
        @DisplayName("selects the first matching branch")
        void selectsFirstMatch() {
            WorkflowNode node = decisionNode();

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of("amount", 15000), new TestContexts.RecordingLogWriter()));

            assertEquals("approved", result.selectedBranch());
            assertEquals("approved", result.outputs().get("selectedBranch"));
            assertEquals(false, result.outputs().get("usedDefaultBranch"));
        }

        @Test
        @DisplayName("order matters: a later condition wins only when earlier ones do not match")
        void orderDecidesTheWinner() {
            WorkflowNode node = decisionNode();

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of("amount", 500), new TestContexts.RecordingLogWriter()));

            assertEquals("normal", result.selectedBranch());
        }

        @Test
        @DisplayName("falls back to the default branch when nothing matches")
        void usesDefaultBranch() {
            WorkflowNode node = TestContexts.node("decision-1", NodeTypes.DECISION);
            node.setConditions(List.of(new DecisionCondition("premium", "customerType == 'PREMIUM'")));
            node.setDefaultBranch("standard");

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of("customerType", "BASIC"), new TestContexts.RecordingLogWriter()));

            assertEquals("standard", result.selectedBranch());
            assertEquals(true, result.outputs().get("usedDefaultBranch"));
        }

        @Test
        @DisplayName("fails when nothing matches and no default is declared")
        void failsWithoutMatchOrDefault() {
            WorkflowNode node = TestContexts.node("decision-1", NodeTypes.DECISION);
            node.setConditions(List.of(new DecisionCondition("premium", "customerType == 'PREMIUM'")));

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of("customerType", "BASIC"), new TestContexts.RecordingLogWriter()));

            assertTrue(result.isFailed());
            assertEquals("DECISION_NO_MATCH", result.errorCode());
        }

        @Test
        @DisplayName("an expression referencing an unset variable is treated as unmatched, not as a failure")
        void unresolvableConditionIsFalse() {
            WorkflowNode node = TestContexts.node("decision-1", NodeTypes.DECISION);
            node.setConditions(List.of(new DecisionCondition("approved", "neverSetVariable > 1")));
            node.setDefaultBranch("fallback");

            TestContexts.RecordingLogWriter logWriter = new TestContexts.RecordingLogWriter();
            NodeExecutionResult result = executor.execute(node, contextWith(node, Map.of(), logWriter));

            assertEquals("fallback", result.selectedBranch());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evaluations =
                    (List<Map<String, Object>>) result.outputs().get("evaluations");
            assertEquals(false, evaluations.get(0).get("matched"));
            assertTrue(evaluations.get(0).containsKey("error"),
                    "the reason must be recorded so a typo is diagnosable");
        }

        @Test
        @DisplayName("reads conditions from raw configuration when the typed field is empty")
        void readsConditionsFromConfiguration() {
            WorkflowNode node = TestContexts.node("decision-1", NodeTypes.DECISION);
            node.setConfiguration(Map.of("conditions", List.of(
                    Map.of("branch", "approved", "expression", "amount > 10000"),
                    Map.of("branch", "normal", "expression", "amount <= 10000"))));

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of("amount", 20000), new TestContexts.RecordingLogWriter()));

            assertEquals("approved", result.selectedBranch());
        }
    }

    @Nested
    @DisplayName("End node")
    class EndNode {

        private final EndNodeExecutor executor = new EndNodeExecutor();

        @Test
        @DisplayName("is terminal, which is how the engine knows the workflow is finished")
        void isTerminal() {
            assertTrue(executor.isTerminal());
            assertEquals(NodeTypes.END, executor.getNodeType());
        }

        @Test
        @DisplayName("assembles the result from configured outputs and publishes it to the output scope")
        void buildsResultFromConfiguration() {
            WorkflowNode node = TestContexts.node("end-1", NodeTypes.END);
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("approved", "${approved}");
            outputs.put("total", "${amount}");
            node.setConfiguration(Map.of("outputs", outputs, "resultStatus", "DONE"));

            WorkflowExecutionContext context = contextWith(node,
                    Map.of("approved", true, "amount", 15000), new TestContexts.RecordingLogWriter());
            NodeExecutionResult result = executor.execute(node, context);

            assertTrue(result.isSuccess());
            assertEquals(true, result.outputs().get("approved"));
            assertEquals(15000, result.outputs().get("total"));
            assertEquals("DONE", result.outputs().get("resultStatus"));
            assertEquals(true, context.variables().find("output.approved").orElse(null));
        }

        @Test
        @DisplayName("copies declared variable paths under their final segment")
        void copiesDeclaredOutputs() {
            WorkflowNode node = TestContexts.node("end-1", NodeTypes.END);
            node.setOutputs(List.of("workflow.approved", "${workflow.amount}"));

            NodeExecutionResult result = executor.execute(node,
                    contextWith(node, Map.of("approved", true, "amount", 99),
                            new TestContexts.RecordingLogWriter()));

            assertEquals(true, result.outputs().get("approved"));
            assertEquals(99, result.outputs().get("amount"));
        }
    }
}
