package com.orchpilot.workflow.sdk.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Design-time description of a node type, published by a plugin and served to user interfaces by
 * {@code GET /api/nodes}.
 *
 * <p>Because plugins describe their own configuration, a front end can render a palette and a
 * property panel for a node type that did not exist when the front end was built. That is why this
 * type carries UI metadata alongside the JSON-schema-shaped {@link #configurationSchema()}.
 *
 * @since 1.0.0
 */
public final class NodeDefinition {

    private final String nodeType;
    private final String displayName;
    private final String category;
    private final String icon;
    private final String description;
    private final Map<String, Object> configurationSchema;
    private final List<String> outputPorts;
    private final List<String> outputVariables;
    private final boolean idempotent;
    private final boolean supportsRetry;
    private final boolean supportsAI;
    private final boolean destructive;

    private NodeDefinition(Builder builder) {
        this.nodeType = requireText(builder.nodeType, "nodeType");
        this.displayName = builder.displayName == null ? builder.nodeType : builder.displayName;
        this.category = builder.category == null ? "General" : builder.category;
        this.icon = builder.icon;
        this.description = builder.description == null ? "" : builder.description;
        this.configurationSchema = builder.configurationSchema == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.configurationSchema));
        this.outputPorts = List.copyOf(builder.outputPorts);
        this.outputVariables = List.copyOf(builder.outputVariables);
        this.idempotent = builder.idempotent;
        this.supportsRetry = builder.supportsRetry;
        this.supportsAI = builder.supportsAI;
        this.destructive = builder.destructive;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /**
     * @param nodeType globally unique node type identifier, e.g. {@code SENDGRID_EMAIL}
     * @return a new builder
     */
    public static Builder builder(String nodeType) {
        return new Builder(nodeType);
    }

    /** @return globally unique node type identifier used in workflow definitions */
    public String nodeType() {
        return nodeType;
    }

    /** @return human-readable label for the node palette */
    public String displayName() {
        return displayName;
    }

    /** @return palette grouping, e.g. {@code Communication} */
    public String category() {
        return category;
    }

    /** @return icon hint for the front end, or {@code null} */
    public String icon() {
        return icon;
    }

    /** @return human-readable description */
    public String description() {
        return description;
    }

    /** @return unmodifiable JSON-schema-shaped configuration description */
    public Map<String, Object> configurationSchema() {
        return configurationSchema;
    }

    /**
     * @return named outgoing branches this node can select, empty for single-exit nodes
     */
    public List<String> outputPorts() {
        return outputPorts;
    }

    /**
     * @return output variable names this node publishes, used for design-time variable pickers
     */
    public List<String> outputVariables() {
        return outputVariables;
    }

    /**
     * @return whether re-executing with identical configuration is side-effect free; when
     *         {@code false} the engine applies its idempotency guard on retries and resumes
     */
    public boolean idempotent() {
        return idempotent;
    }

    /** @return whether retrying this node type is meaningful */
    public boolean supportsRetry() {
        return supportsRetry;
    }

    /**
     * @return whether this node type may be offered to an AI Agent as a tool
     *
     * <p>Advisory: a plugin author sets it to signal a node is a good agent tool. The AI Agent never receives a
     * tool automatically regardless — an operator selects tools explicitly and authorization is always checked.
     */
    public boolean supportsAI() {
        return supportsAI;
    }

    /**
     * @return whether this node has consequential side effects (deletes data, sends a message, moves money) that
     *         warrant human approval when it is run as an AI Agent tool
     *
     * <p>Advisory, and used only to gate tool calls: in a supervised agent, a destructive tool is not run until it
     * is approved. It does not change how the node behaves when a human places it in a workflow.
     */
    public boolean destructive() {
        return destructive;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NodeDefinition)) {
            return false;
        }
        return nodeType.equals(((NodeDefinition) o).nodeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeType);
    }

    @Override
    public String toString() {
        return "NodeDefinition{" + nodeType + " (" + category + ")}";
    }

    /**
     * Mutable builder for {@link NodeDefinition}.
     *
     * @since 1.0.0
     */
    public static final class Builder {

        private final String nodeType;
        private String displayName;
        private String category;
        private String icon;
        private String description;
        private Map<String, Object> configurationSchema;
        private final java.util.List<String> outputPorts = new java.util.ArrayList<>();
        private final java.util.List<String> outputVariables = new java.util.ArrayList<>();
        private boolean idempotent;
        private boolean supportsRetry = true;
        private boolean supportsAI = false;
        private boolean destructive = false;

        private Builder(String nodeType) {
            this.nodeType = nodeType;
        }

        /** @param value palette label @return this builder */
        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        /** @param value palette grouping @return this builder */
        public Builder category(String value) {
            this.category = value;
            return this;
        }

        /** @param value icon hint @return this builder */
        public Builder icon(String value) {
            this.icon = value;
            return this;
        }

        /** @param value human-readable description @return this builder */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /** @param schema JSON-schema-shaped configuration description @return this builder */
        public Builder configurationSchema(Map<String, Object> schema) {
            this.configurationSchema = schema;
            return this;
        }

        /** @param ports named outgoing branches @return this builder */
        public Builder outputPorts(String... ports) {
            if (ports != null) {
                this.outputPorts.addAll(java.util.Arrays.asList(ports));
            }
            return this;
        }

        /** @param names output variable names @return this builder */
        public Builder outputVariables(String... names) {
            if (names != null) {
                this.outputVariables.addAll(java.util.Arrays.asList(names));
            }
            return this;
        }

        /** @param value whether re-execution is side-effect free @return this builder */
        public Builder idempotent(boolean value) {
            this.idempotent = value;
            return this;
        }

        /** @param value whether retrying is meaningful @return this builder */
        public Builder supportsRetry(boolean value) {
            this.supportsRetry = value;
            return this;
        }

        /** @param value whether this node may be offered to an AI Agent as a tool @return this builder */
        public Builder supportsAI(boolean value) {
            this.supportsAI = value;
            return this;
        }

        /** @param value whether this node has consequential side effects warranting approval @return this builder */
        public Builder destructive(boolean value) {
            this.destructive = value;
            return this;
        }

        /** @return an immutable node definition */
        public NodeDefinition build() {
            return new NodeDefinition(this);
        }
    }
}
