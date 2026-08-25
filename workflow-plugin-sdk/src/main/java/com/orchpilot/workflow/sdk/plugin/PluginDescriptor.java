package com.orchpilot.workflow.sdk.plugin;

import com.orchpilot.workflow.sdk.node.NodeDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Immutable identity of one loaded plugin version, together with the node types it contributes.
 *
 * <p>Produced by the engine from the plugin instance after {@code initialize}, and used for the
 * registry index, the {@code /api/nodes} catalogue and audit records.
 *
 * @since 1.0.0
 */
public final class PluginDescriptor {

    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final PluginType type;
    private final int apiVersion;
    private final List<NodeDefinition> nodeDefinitions;

    private PluginDescriptor(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.version = requireText(builder.version, "version");
        this.name = builder.name == null ? builder.id : builder.name;
        this.description = builder.description == null ? "" : builder.description;
        this.type = builder.type == null ? PluginType.NODE : builder.type;
        this.apiVersion = builder.apiVersion;
        this.nodeDefinitions = List.copyOf(builder.nodeDefinitions);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PluginDescriptor." + field + " must not be blank");
        }
        return value;
    }

    /**
     * @param id      plugin id
     * @param version plugin version
     * @return a new builder
     */
    public static Builder builder(String id, String version) {
        return new Builder(id, version);
    }

    /** @return plugin id, stable across versions */
    public String id() {
        return id;
    }

    /** @return human-readable plugin name */
    public String name() {
        return name;
    }

    /** @return plugin version */
    public String version() {
        return version;
    }

    /** @return human-readable description */
    public String description() {
        return description;
    }

    /** @return extension category */
    public PluginType type() {
        return type;
    }

    /** @return plugin API version the implementation was built against */
    public int apiVersion() {
        return apiVersion;
    }

    /** @return unmodifiable list of contributed node types, empty for non-node plugins */
    public List<NodeDefinition> nodeDefinitions() {
        return nodeDefinitions;
    }

    /** @return {@code id:version}, the registry coordinate of this plugin version */
    public String coordinate() {
        return id + ":" + version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PluginDescriptor)) {
            return false;
        }
        PluginDescriptor other = (PluginDescriptor) o;
        return id.equals(other.id) && version.equals(other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return "PluginDescriptor{" + coordinate() + ", type=" + type + "}";
    }

    /**
     * Mutable builder for {@link PluginDescriptor}.
     *
     * @since 1.0.0
     */
    public static final class Builder {

        private final String id;
        private final String version;
        private String name;
        private String description;
        private PluginType type;
        private int apiVersion = PluginApi.VERSION;
        private List<NodeDefinition> nodeDefinitions = List.of();

        private Builder(String id, String version) {
            this.id = id;
            this.version = version;
        }

        /** @param value human-readable name @return this builder */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** @param value human-readable description @return this builder */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /** @param value extension category @return this builder */
        public Builder type(PluginType value) {
            this.type = value;
            return this;
        }

        /** @param value plugin API version @return this builder */
        public Builder apiVersion(int value) {
            this.apiVersion = value;
            return this;
        }

        /** @param values contributed node types; {@code null} is treated as empty @return this builder */
        public Builder nodeDefinitions(List<NodeDefinition> values) {
            this.nodeDefinitions = values == null ? List.of() : List.copyOf(values);
            return this;
        }

        /** @return an immutable descriptor */
        public PluginDescriptor build() {
            return new PluginDescriptor(this);
        }
    }
}
