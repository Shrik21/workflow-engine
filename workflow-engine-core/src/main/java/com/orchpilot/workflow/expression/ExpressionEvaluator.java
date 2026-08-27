package com.orchpilot.workflow.expression;

import com.orchpilot.workflow.exception.ExpressionEvaluationException;

import java.util.Map;

/**
 * Evaluates the boolean expressions that drive decision branches and edge guards.
 *
 * <p>An interface rather than a concrete class so the expression dialect is a replaceable decision.
 * The shipped implementation is SpEL restricted to property reads; swapping in MVEL or a
 * purpose-built parser changes one bean and no workflow definitions.
 */
public interface ExpressionEvaluator {

    /**
     * @param expression expression text, e.g. {@code amount > 10000}
     * @param root       variables the expression may read
     * @return the result coerced to boolean; a {@code null} result is false
     * @throws ExpressionEvaluationException when the expression cannot be parsed or evaluated, or
     *                                       when its result is not boolean-coercible
     */
    boolean evaluateBoolean(String expression, Map<String, Object> root);

    /**
     * @param expression expression text
     * @param root       variables the expression may read
     * @return the raw result, possibly {@code null}
     * @throws ExpressionEvaluationException when the expression cannot be parsed or evaluated
     */
    Object evaluate(String expression, Map<String, Object> root);

    /**
     * Parses an expression and rejects constructs the evaluator will not permit, without evaluating
     * it. Used at publish time so an author learns about a bad expression before an execution reaches
     * it at three in the morning.
     *
     * @param expression expression text
     * @throws ExpressionEvaluationException when the expression is unusable
     */
    void validate(String expression);
}
