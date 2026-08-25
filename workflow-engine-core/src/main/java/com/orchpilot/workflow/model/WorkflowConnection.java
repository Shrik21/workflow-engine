package com.orchpilot.workflow.model;

/**
 * A directed edge between two nodes.
 *
 * <p>{@code sourcePort} is how branching works: a decision node returns a branch name and the engine
 * follows the edge whose {@code sourcePort} equals it. An edge with no {@code sourcePort} is the
 * default edge, used by single-exit nodes and as the fallback when no branch matches.
 */
public class WorkflowConnection {

    private String id;
    private String source;
    private String sourcePort;
    private String target;
    private String label;

    /**
     * Optional guard expression. When present the edge is only followed if it evaluates true, which
     * allows conditional routing without a dedicated decision node.
     */
    private String condition;

    public WorkflowConnection() {
    }

    public WorkflowConnection(String source, String target) {
        this.source = source;
        this.target = target;
    }

    public WorkflowConnection(String source, String sourcePort, String target) {
        this.source = source;
        this.sourcePort = sourcePort;
        this.target = target;
    }

    /** @return whether this edge is the unconditional default exit of its source node */
    public boolean isDefaultEdge() {
        return sourcePort == null || sourcePort.isBlank();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourcePort() {
        return sourcePort;
    }

    public void setSourcePort(String sourcePort) {
        this.sourcePort = sourcePort;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    @Override
    public String toString() {
        return "WorkflowConnection{" + source + (sourcePort == null ? "" : ":" + sourcePort) + " -> " + target + "}";
    }
}
