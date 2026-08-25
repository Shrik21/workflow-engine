package com.orchpilot.workflow.node;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.forms.FormField;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.forms.FormVariableMapper;
import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.task.HumanTask;
import com.orchpilot.workflow.task.HumanTaskService;
import com.orchpilot.workflow.task.TaskAssignment;
import com.orchpilot.workflow.task.TaskAssignmentResolver;
import com.orchpilot.workflow.task.TaskCreationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The human step: raises a task, parks the execution, and applies the submission when it arrives.
 *
 * <p>Three paths, one code body, distinguished only by whether the submitted data is already available:
 *
 * <ul>
 *   <li><b>Data present</b>, because the caller supplied it with the start request or a task was completed: the
 *       node finishes immediately and the submitted fields are written to the variables the <em>form</em>
 *       declares.</li>
 *   <li><b>Data absent and {@code waitForInput} true</b>: a {@link HumanTask} is raised and the node returns
 *       {@code WAITING}. No thread is held, so a form left open for a week costs nothing and survives restarts
 *       and rolling deployments.</li>
 *   <li><b>Data absent and {@code waitForInput} false</b>: the node fails with a definite error rather than
 *       hanging, which is what a fully automated pipeline wants.</li>
 * </ul>
 *
 * <h2>Why the mapping happens here and not in the controller</h2>
 *
 * <p>The submission has to be translated into variables by whoever holds the authoritative form version, and it
 * has to happen on every path. Doing it in the task controller would leave
 * {@code POST /api/executions/{id}/form} — which exists, is authorised, and is what an integration uses —
 * writing raw payload keys straight into the workflow. Doing it here means both paths converge on the same
 * server-side field-to-variable mapping, which is the point of holding the mapping on the server at all: a
 * browser sends values, never destinations.
 *
 * <h2>Nodes that reference no managed form</h2>
 *
 * <p>{@code formId} may name a published form, or it may be an arbitrary string from before the form designer
 * existed. When no form resolves, the node still raises a task — so the wait is discoverable and claimable —
 * and falls back to the older behaviour of treating the submitted keys as node outputs for the output mapping to
 * promote. Refusing to run would break every workflow built before this phase.
 */
@Component
public class FormNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(FormNodeExecutor.class);

    /** Output key holding the whole submission, so authors can map nested fields. */
    public static final String SUBMISSION_OUTPUT = "submission";

    private final FormNodeBinding binding;
    private final FormVariableMapper variableMapper;
    private final HumanTaskService taskService;
    private final TaskAssignmentResolver assignmentResolver;

    public FormNodeExecutor(FormNodeBinding binding, FormVariableMapper variableMapper,
                            HumanTaskService taskService, TaskAssignmentResolver assignmentResolver) {
        this.binding = binding;
        this.variableMapper = variableMapper;
        this.taskService = taskService;
        this.assignmentResolver = assignmentResolver;
    }

    @Override
    public String getNodeType() {
        return NodeTypes.FORM;
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
        Map<String, Object> configuration = context.resolveConfiguration(node);
        String formId = binding.formIdOf(node, configuration);
        Optional<FormVersion> form = binding.resolve(node, configuration);

        Optional<Map<String, Object>> submission = context.consumeSignal(node.getId());
        if (submission.isPresent()) {
            return applySubmission(node, context, formId, form, submission.get());
        }

        if (!node.isWaitForInput()) {
            context.logWarn(node.getId(), getNodeType(),
                    "Form node requires input but waiting is disabled", Map.of("formId", formId));
            return NodeExecutionResult.failure("FORM_INPUT_REQUIRED",
                    "Form '" + formId + "' has no submission and this node is configured not to wait");
        }

        return park(node, context, configuration, formId, form);
    }

    // ------------------------------------------------------------------ submission

    private NodeExecutionResult applySubmission(WorkflowNode node, WorkflowExecutionContext context,
                                                String formId, Optional<FormVersion> form,
                                                Map<String, Object> submitted) {
        List<String> written = new ArrayList<>();
        form.ifPresent(version -> variableMapper.mapFormDataToVariablePaths(version, submitted)
                .forEach((path, value) -> {
                    /*
                     * One path at a time rather than one merged nested map, so a form writing employee.name
                     * does not replace whatever else already lives under employee.
                     */
                    context.variables().set(path, value);
                    written.add(path);
                }));

        Map<String, Object> outputs = new LinkedHashMap<>(submitted);
        // Also expose the whole submission so an output mapping can address nested fields, and so a node that
        // references no managed form behaves exactly as it did before tasks existed.
        outputs.put(SUBMISSION_OUTPUT, new LinkedHashMap<>(submitted));
        outputs.put("formId", formId);
        outputs.put("submitted", Boolean.TRUE);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("formId", formId);
        // Field names and variable paths. Never the values: an execution log is read by anybody who can read
        // the execution, and the values are whatever the form asked a person for.
        details.put("fields", submitted.keySet());
        details.put("variables", written);
        context.logInfo(node.getId(), getNodeType(), "Form submission accepted", details);
        log.debug("Execution {} accepted submission for form {}, writing {} variable(s)",
                context.executionId(), formId, written.size());
        return NodeExecutionResult.success(outputs);
    }

    // ---------------------------------------------------------------------- parking

    private NodeExecutionResult park(WorkflowNode node, WorkflowExecutionContext context,
                                     Map<String, Object> configuration, String formId,
                                     Optional<FormVersion> form) {
        Map<String, Object> prefill = prefill(node, context, form, configuration);
        TaskAssignment assignment = assignmentResolver.resolve(configuration);

        String taskName = text(configuration.get("taskName"));
        if (taskName == null) {
            taskName = form.map(FormVersion::getTitle).orElse(null);
        }
        if (taskName == null) {
            taskName = node.getName() == null ? "Form" : node.getName();
        }

        HumanTask task = taskService.createOrReuse(new TaskCreationRequest(
                context.executionId(),
                context.workflowId(),
                context.workflowVersion(),
                context.definition().getName(),
                node.getId(),
                node.getName(),
                form.map(FormVersion::getFormDefinitionId).orElse(null),
                form.map(FormVersion::getVersion).orElse(0),
                taskName,
                text(configuration.get("taskDescription")) == null
                        ? node.getDescription() : text(configuration.get("taskDescription")),
                prefill,
                assignment,
                context.execution().getTriggeredBy(),
                context.execution().getCorrelationId()));

        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("formId", formId);
        pending.put("taskId", task.getId());
        pending.put("prefill", prefill);
        form.ifPresent(version -> {
            pending.put("formDefinitionId", version.getFormDefinitionId());
            pending.put("formVersion", version.getVersion());
        });
        if (task.getAssigneeUsername() != null) {
            pending.put("assignee", task.getAssigneeUsername());
        }
        if (task.getExpiresAt() != null) {
            // The engine turns this into the pending signal's own expiry, so the existing "the signal expired"
            // guard on the raw submission endpoint stays consistent with the task's deadline.
            pending.put("expiresInSeconds",
                    Math.max(1, java.time.Duration.between(java.time.Instant.now(),
                            task.getExpiresAt()).getSeconds()));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("formId", formId);
        details.put("taskId", task.getId());
        details.put("status", task.getStatus().name());
        details.put("assignee", task.getAssigneeUsername());
        if (!assignment.problems().isEmpty()) {
            details.put("assignmentProblems", assignment.problems());
        }
        context.logInfo(node.getId(), getNodeType(), "Waiting for a person to complete task "
                + task.getId(), details);
        log.debug("Execution {} parked at node {} awaiting task {}", context.executionId(), node.getId(),
                task.getId());
        return NodeExecutionResult.waiting("Awaiting completion of task '" + task.getId() + "'", pending);
    }

    /**
     * Values to show in the form when it is first opened.
     *
     * <p>Three sources, least specific first, each overriding the last:
     *
     * <ol>
     *   <li>the field's own default, with placeholders resolved;</li>
     *   <li>whatever the mapped variable currently holds, which is what makes a read-only field show a value an
     *       earlier node computed;</li>
     *   <li>the node's input mapping, which is the author saying explicitly what this field starts as.</li>
     * </ol>
     *
     * <p>A saved draft overrides all three, but that is applied when the task is read rather than here: the draft
     * did not exist when the task was raised.
     */
    private Map<String, Object> prefill(WorkflowNode node, WorkflowExecutionContext context,
                                        Optional<FormVersion> form, Map<String, Object> configuration) {
        Map<String, Object> prefill = new LinkedHashMap<>();

        form.ifPresent(version -> {
            for (FormField field : version.getFields()) {
                if (!field.collectsValue() || field.getName() == null) {
                    continue;
                }
                if (field.getDefaultValue() != null) {
                    prefill.put(field.getName(), context.resolve(field.getDefaultValue()));
                }
                if (field.isMapped()) {
                    context.variables().find(field.getVariable())
                            .ifPresent(value -> prefill.put(field.getName(), value));
                }
            }
        });

        prefill.putAll(context.mapper().applyInputMapping(node.getInputMapping(), context.variables()));

        // Retained for a node authored before the designer existed, which declared its fields inline.
        if (configuration.containsKey("prefill") && configuration.get("prefill") instanceof Map<?, ?> map) {
            map.forEach((key, value) -> prefill.put(String.valueOf(key), value));
        }
        return prefill;
    }

    // --------------------------------------------------------------------- helpers

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() || "null".equals(string) ? null : string;
    }
}
