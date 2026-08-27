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
 * Terminates the workflow and assembles its result.
 *
 * <p>Two ways to declare the result, both resolved before they are stored:
 *
 * <ul>
 *   <li>{@code outputs} in the configuration, as {@code name -> template}, which gives full control
 *       over the shape of the result;</li>
 *   <li>the node's {@code outputs} list of variable paths, copied under their final path segment, which
 *       is the terse form for "return these variables".</li>
 * </ul>
 *
 * <p>{@link #isTerminal()} is how the engine knows the workflow is finished. It does not test the node
 * type, so a plugin could contribute its own terminal node without the engine changing. Completion
 * status, the result and the completion event are all applied by the engine once this returns, keeping
 * event publication in one place rather than duplicated across every path that can end a run.
 */
@Component
public class EndNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(EndNodeExecutor.class);

    /** Configuration key holding an explicit result shape. */
    public static final String OUTPUTS_KEY = "outputs";

    /** Configuration key holding a business status recorded alongside the result. */
    public static final String RESULT_STATUS_KEY = "resultStatus";

    @Override
    public String getNodeType() {
        return NodeTypes.END;
    }

    @Override
    public boolean isTerminal() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
        Map<String, Object> configuration = context.resolveConfiguration(node);
        Map<String, Object> result = new LinkedHashMap<>();

        Object declared = configuration.get(OUTPUTS_KEY);
        if (declared instanceof Map) {
            result.putAll((Map<String, Object>) declared);
        }

        for (String path : node.getOutputs()) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String name = lastSegment(path);
            context.variables().find(stripPlaceholder(path))
                    .ifPresent(value -> result.put(name, value));
        }

        Object resultStatus = configuration.get(RESULT_STATUS_KEY);
        if (resultStatus != null) {
            result.put(RESULT_STATUS_KEY, resultStatus);
        }

        // Publish into the output scope so downstream tooling can read ${output.*} and so the values
        // are part of the persisted variable snapshot, not only of the execution result.
        result.forEach((name, value) -> context.variables()
                .set(VariableScope.OUTPUT.key() + "." + name, value));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outputKeys", result.keySet());
        context.logInfo(node.getId(), getNodeType(), "Workflow reached end node", details);
        log.debug("Execution {} reached end node {} with {} output(s)",
                context.executionId(), node.getId(), result.size());

        Map<String, Object> outputs = new LinkedHashMap<>(result);
        outputs.put("completedAt", java.time.Instant.now().toString());
        return NodeExecutionResult.success(outputs);
    }

    private static String lastSegment(String path) {
        String cleaned = stripPlaceholder(path);
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 && dot < cleaned.length() - 1 ? cleaned.substring(dot + 1) : cleaned;
    }

    private static String stripPlaceholder(String path) {
        String trimmed = path.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return trimmed.substring(2, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
