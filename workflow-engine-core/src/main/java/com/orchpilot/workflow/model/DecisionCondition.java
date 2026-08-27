package com.orchpilot.workflow.model;

/**
 * One branch of a decision node: an expression and the outgoing port to take when it holds.
 *
 * <p>Conditions are evaluated in list order and the first match wins, so ordering is significant and
 * authors can rely on it rather than writing mutually exclusive expressions.
 */
public class DecisionCondition {

    private String branch;
    private String expression;
    private String description;

    public DecisionCondition() {
    }

    public DecisionCondition(String branch, String expression) {
        this.branch = branch;
        this.expression = expression;
    }

    /** @return outgoing port name, matched against {@code sourcePort} on connections */
    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    /**
     * @return boolean expression over execution variables, e.g. {@code amount > 10000}, evaluated in
     *         a read-only expression context
     */
    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
