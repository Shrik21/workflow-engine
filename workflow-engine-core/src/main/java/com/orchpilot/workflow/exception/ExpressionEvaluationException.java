package com.orchpilot.workflow.exception;

/**
 * An expression could not be parsed or evaluated.
 *
 * <p>Also thrown when an expression uses a construct the safe evaluator refuses, such as a type
 * reference or a bean reference.
 */
public class ExpressionEvaluationException extends WorkflowEngineException {

    private static final long serialVersionUID = 1L;

    private final String expression;

    public ExpressionEvaluationException(String expression, String message, Throwable cause) {
        super("EXPRESSION_INVALID", "Cannot evaluate '" + expression + "': " + message, cause);
        this.expression = expression;
    }

    public ExpressionEvaluationException(String expression, String message) {
        this(expression, message, null);
    }

    /** @return the offending expression */
    public String getExpression() {
        return expression;
    }
}
