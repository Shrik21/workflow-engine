package com.orchpilot.workflow.node;

import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.variable.VariableScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Initialises the workflow scope and hands control to the first real node.
 *
 * <p>Three sources of initial state, applied in increasing precedence:
 *
 * <ol>
 *   <li>variables declared on the workflow definition, already seeded by the engine;</li>
 *   <li>{@code variables} in the start node's configuration, which lets a designer set defaults
 *       without editing workflow-level settings;</li>
 *   <li>the start node's input mapping, which pulls values out of the execution input.</li>
 * </ol>
 *
 * <p>Defaults never overwrite a value the caller supplied: a start node's declared default for
 * {@code region} must not clobber the {@code region} the API caller passed in.
 */
@Component
public class StartNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(StartNodeExecutor.class);

    @Override
    public String getNodeType() {
        return NodeTypes.START;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
        Map<String, Object> configuration = context.resolveConfiguration(node);

        Object declared = configuration.get("variables");
        int defaultsApplied = 0;
        if (declared instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) declared).entrySet()) {
                String name = entry.getKey();
                if (context.variables().find(VariableScope.WORKFLOW.key() + "." + name).isEmpty()) {
                    context.variables().set(VariableScope.WORKFLOW.key() + "." + name, entry.getValue());
                    defaultsApplied++;
                }
            }
        }

        Map<String, Object> mapped = context.mapper().applyInputMapping(node.getInputMapping(), context.variables());
        mapped.forEach((name, value) -> context.variables().set(VariableScope.WORKFLOW.key() + "." + name, value));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("startedAt", context.startedAt().toString());
        outputs.put("mode", String.valueOf(context.mode()));
        outputs.put("variablesInitialised", defaultsApplied + mapped.size());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("defaultsApplied", defaultsApplied);
        details.put("mappedInputs", mapped.keySet());
        context.logInfo(node.getId(), getNodeType(), "Workflow execution started", details);
        log.debug("Execution {} started at node {}", context.executionId(), node.getId());

        return NodeExecutionResult.success(outputs);
    }
}
