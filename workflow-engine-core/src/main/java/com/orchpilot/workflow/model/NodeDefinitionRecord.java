package com.orchpilot.workflow.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted form of a node type contributed by a plugin.
 *
 * <p>Stored on the plugin version document so that {@code GET /api/nodes} and workflow validation work
 * for installed-but-not-loaded versions, and so the node catalogue survives a restart before plugins
 * finish reloading. It is a mirror of the SDK's {@code NodeDefinition}, kept separate on purpose: the
 * SDK type is a public contract and must not be forced to carry persistence annotations.
 */
public class NodeDefinitionRecord {

    private String nodeType;
    private String displayName;
    private String category;
    private String icon;
    private String description;
    private Map<String, Object> configurationSchema = new LinkedHashMap<>();
    private List<String> outputPorts = new ArrayList<>();
    private List<String> outputVariables = new ArrayList<>();
    private boolean idempotent;
    private boolean supportsRetry = true;

    public NodeDefinitionRecord() {
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getConfigurationSchema() {
        return configurationSchema;
    }

    public void setConfigurationSchema(Map<String, Object> configurationSchema) {
        this.configurationSchema = configurationSchema == null ? new LinkedHashMap<>() : configurationSchema;
    }

    public List<String> getOutputPorts() {
        return outputPorts;
    }

    public void setOutputPorts(List<String> outputPorts) {
        this.outputPorts = outputPorts == null ? new ArrayList<>() : outputPorts;
    }

    public List<String> getOutputVariables() {
        return outputVariables;
    }

    public void setOutputVariables(List<String> outputVariables) {
        this.outputVariables = outputVariables == null ? new ArrayList<>() : outputVariables;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public void setIdempotent(boolean idempotent) {
        this.idempotent = idempotent;
    }

    public boolean isSupportsRetry() {
        return supportsRetry;
    }

    public void setSupportsRetry(boolean supportsRetry) {
        this.supportsRetry = supportsRetry;
    }
}
