package com.orchpilot.workflow.node;

import com.orchpilot.workflow.exception.ExpressionEvaluationException;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.DecisionCondition;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses one of several outgoing branches.
 *
 * <p>Conditions are evaluated in declaration order and the first true one wins, so an author can put
 * the specific case first and a catch-all last instead of writing mutually exclusive expressions. The
 * chosen branch name is returned in the result and the engine follows the edge whose {@code sourcePort}
 * matches it.
 *
 * <p>An expression that fails to evaluate is treated as false rather than failing the node, and is
 * logged as a warning. A comparison against a variable a previous branch never set is a normal
 * consequence of branching, and failing the workflow for it would make decision nodes unusable in any
 * graph with optional paths. A genuinely broken expression is caught at publish time by the validator,
 * which is the right place for it.
 */
@Component
public class DecisionNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(DecisionNodeExecutor.class);

    /** Output key naming the branch that was taken. */
    public static final String SELECTED_BRANCH_OUTPUT = "selectedBranch";

    @Override
    public String getNodeType() {
        return NodeTypes.DECISION;
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
        List<DecisionCondition> conditions = effectiveConditions(node, context);
        if (conditions.isEmpty() && (node.getDefaultBranch() == null || node.getDefaultBranch().isBlank())) {
            return NodeExecutionResult.failure("DECISION_NOT_CONFIGURED",
                    "Decision node '" + node.getId() + "' declares neither conditions nor a default branch");
        }

        List<Map<String, Object>> evaluations = new ArrayList<>(conditions.size());
        for (DecisionCondition condition : conditions) {
            String branch = condition.getBranch();
            String expression = condition.getExpression();
            if (branch == null || branch.isBlank() || expression == null || expression.isBlank()) {
                continue;
            }
            boolean matched;
            String failure = null;
            try {
                matched = context.evaluateCondition(expression);
            } catch (ExpressionEvaluationException ex) {
                matched = false;
                failure = ex.getMessage();
                log.warn("Decision {} treated condition '{}' as false: {}", node.getId(), expression,
                        ex.getMessage());
            }
            Map<String, Object> evaluation = new LinkedHashMap<>();
            evaluation.put("branch", branch);
            evaluation.put("expression", expression);
            evaluation.put("matched", matched);
            if (failure != null) {
                evaluation.put("error", failure);
            }
            evaluations.add(evaluation);

            if (matched) {
                return success(node, context, branch, evaluations, false);
            }
        }

        String fallback = node.getDefaultBranch();
        if (fallback != null && !fallback.isBlank()) {
            return success(node, context, fallback, evaluations, true);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("evaluated", evaluations);
        context.logError(node.getId(), getNodeType(), "No decision branch matched and no default is set",
                details);
        return NodeExecutionResult.failure("DECISION_NO_MATCH",
                "No condition matched on decision node '" + node.getId() + "' and no default branch is set");
    }

    private NodeExecutionResult success(WorkflowNode node, WorkflowExecutionContext context, String branch,
                                        List<Map<String, Object>> evaluations, boolean viaDefault) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put(SELECTED_BRANCH_OUTPUT, branch);
        outputs.put("usedDefaultBranch", viaDefault);
        outputs.put("evaluations", evaluations);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("branch", branch);
        details.put("usedDefaultBranch", viaDefault);
        details.put("evaluated", evaluations);
        context.logInfo(node.getId(), getNodeType(), "Decision selected branch '" + branch + "'", details);

        return NodeExecutionResult.branch(branch, outputs);
    }

    /**
     * Reads conditions from the node's typed field, falling back to a {@code conditions} list in the
     * configuration map so that a workflow authored as raw JSON works either way.
     */
    @SuppressWarnings("unchecked")
    private List<DecisionCondition> effectiveConditions(WorkflowNode node, WorkflowExecutionContext context) {
        if (!node.getConditions().isEmpty()) {
            return node.getConditions();
        }
        Object raw = node.getConfiguration().get("conditions");
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<DecisionCondition> parsed = new ArrayList<>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) item;
            Object branch = entry.get("branch");
            Object expression = entry.get("expression");
            if (branch == null || expression == null) {
                continue;
            }
            // Only the branch name is a template; the expression is evaluated, not interpolated.
            parsed.add(new DecisionCondition(context.resolveText(String.valueOf(branch)),
                    String.valueOf(expression)));
        }
        return parsed;
    }
}
