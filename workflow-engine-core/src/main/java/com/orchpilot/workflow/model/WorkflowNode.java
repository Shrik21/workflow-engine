package com.orchpilot.workflow.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node in a workflow definition.
 *
 * <p>A single shape covers built-in and plugin nodes. The engine reads {@code type} to find an
 * executor and hands the whole node to it; fields that only matter to one kind of node, such as
 * {@code conditions} or {@code pluginId}, are simply unused by the others. That is what keeps the
 * engine free of per-type branching.
 */
public class WorkflowNode {

    private String id;

    /**
     * Node type. One of {@link NodeTypes}, the literal {@code PLUGIN}, or a node type contributed by
     * a plugin such as {@code SENDGRID_EMAIL}.
     */
    private String type;

    private String name;
    private String description;

    /** Plugin id, required when {@code type} is {@code PLUGIN}. */
    private String pluginId;

    /**
     * Exact plugin version to execute. Pinning it is what stops a plugin upgrade from silently
     * changing the behaviour of a live workflow.
     */
    private String pluginVersion;

    /** Raw configuration, possibly containing {@code ${...}} placeholders. */
    private Map<String, Object> configuration = new LinkedHashMap<>();

    /** Variable paths to copy into the node's inputs before execution. */
    private Map<String, String> inputMapping = new LinkedHashMap<>();

    /** Node output names to copy into variable paths after a successful execution. */
    private Map<String, String> outputMapping = new LinkedHashMap<>();

    /** Ordered branch conditions, for decision nodes. */
    private List<DecisionCondition> conditions = new ArrayList<>();

    /** Branch taken when no condition matches. */
    private String defaultBranch;

    /** Form identifier, for form nodes. */
    private String formId;

    /**
     * Published form version this node renders, or null to follow the newest published one.
     *
     * <p>A first-class field rather than a configuration key, for the same reason {@code pluginVersion} is:
     * pinning a version is how an author stops a later edit from changing what a live workflow shows, and that
     * deserves to be visible in the node rather than buried in a map the engine has to go looking through.
     *
     * <p>{@code configuration.formVersion} is still honoured when this is null, because workflows authored
     * before this field existed put it there and must keep working.
     */
    private Integer formVersion;

    /** Whether a form node parks the execution instead of failing when input is absent. */
    private boolean waitForInput = true;

    /** Output variable names an end node copies into the execution result. */
    private List<String> outputs = new ArrayList<>();

    private RetryPolicy retry;
    private ErrorPolicy errorPolicy = ErrorPolicy.FAIL_WORKFLOW;

    /** Node to execute to undo this node's work, for {@link ErrorPolicy#COMPENSATE}. */
    private String compensationNodeId;

    /** Per-node execution budget; {@code null} uses the engine default. */
    private Long timeoutMillis;

    /** Designer coordinates and other presentation data. Ignored by the engine. */
    private Map<String, Object> presentation = new LinkedHashMap<>();

    public WorkflowNode() {
    }

    public WorkflowNode(String id, String type, String name) {
        this.id = id;
        this.type = type;
        this.name = name;
    }

    /**
     * @return {@code true} when this node delegates to a plugin, either through the {@code PLUGIN}
     *         marker type or by declaring a plugin coordinate directly
     */
    public boolean isPluginNode() {
        return NodeTypes.PLUGIN.equals(type) || (pluginId != null && !pluginId.isBlank());
    }

    /**
     * @return the retry policy, never {@code null}; nodes without one do not retry
     */
    public RetryPolicy effectiveRetry() {
        return retry == null ? RetryPolicy.disabled() : retry;
    }

    /**
     * @return the error policy, never {@code null}
     */
    public ErrorPolicy effectiveErrorPolicy() {
        return errorPolicy == null ? ErrorPolicy.FAIL_WORKFLOW : errorPolicy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public void setPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration == null ? new LinkedHashMap<>() : configuration;
    }

    public Map<String, String> getInputMapping() {
        return inputMapping;
    }

    public void setInputMapping(Map<String, String> inputMapping) {
        this.inputMapping = inputMapping == null ? new LinkedHashMap<>() : inputMapping;
    }

    public Map<String, String> getOutputMapping() {
        return outputMapping;
    }

    public void setOutputMapping(Map<String, String> outputMapping) {
        this.outputMapping = outputMapping == null ? new LinkedHashMap<>() : outputMapping;
    }

    public List<DecisionCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<DecisionCondition> conditions) {
        this.conditions = conditions == null ? new ArrayList<>() : conditions;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getFormId() {
        return formId;
    }

    public void setFormId(String formId) {
        this.formId = formId;
    }

    public Integer getFormVersion() {
        return formVersion;
    }

    public void setFormVersion(Integer formVersion) {
        this.formVersion = formVersion;
    }

    public boolean isWaitForInput() {
        return waitForInput;
    }

    public void setWaitForInput(boolean waitForInput) {
        this.waitForInput = waitForInput;
    }

    public List<String> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<String> outputs) {
        this.outputs = outputs == null ? new ArrayList<>() : outputs;
    }

    public RetryPolicy getRetry() {
        return retry;
    }

    public void setRetry(RetryPolicy retry) {
        this.retry = retry;
    }

    public ErrorPolicy getErrorPolicy() {
        return errorPolicy;
    }

    public void setErrorPolicy(ErrorPolicy errorPolicy) {
        this.errorPolicy = errorPolicy;
    }

    public String getCompensationNodeId() {
        return compensationNodeId;
    }

    public void setCompensationNodeId(String compensationNodeId) {
        this.compensationNodeId = compensationNodeId;
    }

    public Long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(Long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public Map<String, Object> getPresentation() {
        return presentation;
    }

    public void setPresentation(Map<String, Object> presentation) {
        this.presentation = presentation == null ? new LinkedHashMap<>() : presentation;
    }

    @Override
    public String toString() {
        return "WorkflowNode{" + id + ":" + type + "}";
    }
}
