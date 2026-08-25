package com.orchpilot.workflow.sdk.manifest;

import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.plugin.PluginType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a plugin JAR declares about itself in {@code META-INF/workflow-plugin.json}.
 *
 * <h2>Why this exists alongside {@link com.orchpilot.workflow.sdk.plugin.PluginDescriptor}</h2>
 *
 * <p>They answer the same questions from opposite directions, and the distinction is the reason the plugin
 * server can be safe:
 *
 * <ul>
 *   <li>A <b>manifest</b> is <em>declared</em>. It is a JSON file read out of the archive with no class
 *       loading, so it can be parsed by a service that must never execute the code it stores.</li>
 *   <li>A <b>descriptor</b> is <em>observed</em>. The engine builds one from a live plugin instance after
 *       {@code initialize}, which is authoritative about behaviour but requires running the plugin.</li>
 * </ul>
 *
 * <p>The engine cross-checks one against the other when it loads a plugin. A manifest claiming
 * {@code pluginId: sendgrid} whose instance reports {@code openai} is a JAR whose registry entry describes
 * something other than what would run, and is rejected rather than reconciled.
 *
 * <h2>Parsing is total</h2>
 *
 * <p>{@link #parse(String)} never throws on a well-formed JSON object with missing or oddly typed fields; it
 * collects {@link #problems()} instead. A registry rejecting an upload owes the author every problem in the
 * file, not the first one, and a stack trace is not a review.
 *
 * @since 1.0.0
 */
public final class PluginManifest {

    /** Where the manifest lives inside a plugin archive. */
    public static final String LOCATION = "META-INF/workflow-plugin.json";

    private final String pluginId;
    private final String name;
    private final String version;
    private final String description;
    private final String vendor;
    private final String mainClass;
    private final String sdkVersion;
    private final String javaVersion;
    private final String engineCompatibility;
    private final PluginType pluginType;
    private final List<ManifestNode> nodes;
    private final List<ManifestDependency> dependencies;
    private final Map<String, Object> requestedPermissions;
    private final List<String> problems;

    private PluginManifest(Builder builder) {
        this.pluginId = builder.pluginId;
        this.name = builder.name;
        this.version = builder.version;
        this.description = builder.description;
        this.vendor = builder.vendor;
        this.mainClass = builder.mainClass;
        this.sdkVersion = builder.sdkVersion;
        this.javaVersion = builder.javaVersion;
        this.engineCompatibility = builder.engineCompatibility;
        this.pluginType = builder.pluginType;
        this.nodes = List.copyOf(builder.nodes);
        this.dependencies = List.copyOf(builder.dependencies);
        this.requestedPermissions = Map.copyOf(builder.requestedPermissions);
        this.problems = List.copyOf(builder.problems);
    }

    /**
     * Parses a manifest document.
     *
     * @param json the contents of {@code META-INF/workflow-plugin.json}
     * @return the manifest, whose {@link #problems()} lists everything wrong with it
     */
    public static PluginManifest parse(String json) {
        Builder builder = new Builder();
        Map<String, Object> root;
        try {
            root = Json.parseObject(json);
        } catch (RuntimeException ex) {
            builder.problems.add("The manifest is not a JSON object: " + ex.getMessage());
            return builder.build();
        }
        if (root == null) {
            builder.problems.add("The manifest is empty.");
            return builder.build();
        }

        builder.pluginId = text(root, "pluginId");
        builder.name = text(root, "name");
        builder.version = text(root, "version");
        builder.description = text(root, "description");
        builder.vendor = text(root, "vendor");
        builder.mainClass = text(root, "mainClass");
        builder.sdkVersion = text(root, "sdkVersion");
        builder.javaVersion = text(root, "javaVersion");
        builder.engineCompatibility = text(root, "engineCompatibility");

        String type = text(root, "pluginType");
        if (type != null) {
            try {
                builder.pluginType = PluginType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                builder.problems.add("pluginType '" + type + "' is not one of "
                        + java.util.Arrays.toString(PluginType.values()) + ".");
            }
        }

        Object nodes = root.get("nodes");
        if (nodes instanceof Collection<?> list) {
            int index = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> node) {
                    builder.nodes.add(ManifestNode.from(asStringKeyed(node), index, builder.problems));
                } else {
                    builder.problems.add("nodes[" + index + "] is not an object.");
                }
                index++;
            }
        } else if (nodes != null) {
            builder.problems.add("'nodes' must be an array.");
        }

        Object dependencies = root.get("dependencies");
        if (dependencies instanceof Collection<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> dependency) {
                    builder.dependencies.add(ManifestDependency.from(asStringKeyed(dependency)));
                }
            }
        }

        Object permissions = root.get("permissions");
        if (permissions instanceof Map<?, ?> map) {
            builder.requestedPermissions.putAll(asStringKeyed(map));
        }

        return builder.build();
    }

    /**
     * Checks the manifest's own consistency.
     *
     * <p>Structural rules only: what a registry can know without loading the code or consulting a database.
     * Whether the version already exists, and whether the SDK is compatible with a particular engine, are
     * questions for the caller.
     *
     * @return every problem found, empty when the manifest is acceptable
     */
    public List<String> validate() {
        List<String> found = new ArrayList<>(problems);

        if (isBlank(pluginId)) {
            found.add("pluginId is required.");
        } else {
            if (!pluginId.matches("[a-z0-9][a-z0-9-]{1,63}")) {
                found.add("pluginId '" + pluginId + "' must be 2 to 64 characters of lower-case letters, "
                        + "digits and hyphens, starting with a letter or digit.");
            }
            /*
             * A version inside the id would break every assumption downstream: the registry keys plugins by
             * id, the catalogue reports one latest version per id, and a workflow pins id plus version
             * separately. "sendgrid-1.2.0" would become a plugin that can never be updated.
             */
            if (version != null && pluginId.endsWith("-" + version)) {
                found.add("pluginId '" + pluginId + "' must not contain the version. Use pluginId '"
                        + pluginId.substring(0, pluginId.length() - version.length() - 1)
                        + "' with version '" + version + "'.");
            }
        }

        if (isBlank(version)) {
            found.add("version is required.");
        } else if (com.orchpilot.workflow.sdk.version.SemanticVersion.tryParse(version).isEmpty()) {
            found.add("version '" + version + "' is not a semantic version (MAJOR.MINOR.PATCH).");
        }

        if (isBlank(mainClass)) {
            found.add("mainClass is required.");
        } else if (!mainClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            found.add("mainClass '" + mainClass + "' is not a fully qualified Java class name.");
        }

        if (isBlank(sdkVersion)) {
            found.add("sdkVersion is required, so the engine can refuse a plugin built against an "
                    + "incompatible SDK before loading it.");
        }

        // pluginType(), not the field: an omitted type defaults to NODE when read, so testing the raw field
        // let a manifest with no declared type skip the requirement to contribute a node.
        if (pluginType() == PluginType.NODE && nodes.isEmpty()) {
            found.add("A NODE plugin must declare at least one node, otherwise it contributes nothing.");
        }

        java.util.Set<String> nodeTypes = new java.util.HashSet<>();
        for (ManifestNode node : nodes) {
            if (!nodeTypes.add(node.nodeType())) {
                found.add("nodeType '" + node.nodeType() + "' is declared more than once.");
            }
        }
        return found;
    }

    /** @return whether this manifest is structurally acceptable */
    public boolean isValid() {
        return validate().isEmpty();
    }

    public String pluginId() {
        return pluginId;
    }

    /** @return the display name, falling back to the id */
    public String name() {
        return isBlank(name) ? pluginId : name;
    }

    public String version() {
        return version;
    }

    public String description() {
        return description == null ? "" : description;
    }

    public String vendor() {
        return vendor;
    }

    public String mainClass() {
        return mainClass;
    }

    public String sdkVersion() {
        return sdkVersion;
    }

    public String javaVersion() {
        return javaVersion;
    }

    /** @return an engine version range such as {@code >=1.0.0 <2.0.0}, or null when unconstrained */
    public String engineCompatibility() {
        return engineCompatibility;
    }

    public PluginType pluginType() {
        return pluginType == null ? PluginType.NODE : pluginType;
    }

    public List<ManifestNode> nodes() {
        return nodes;
    }

    public List<ManifestDependency> dependencies() {
        return dependencies;
    }

    /**
     * @return the permissions the plugin asks for. What it is actually granted is decided by an
     *         administrator in the engine, never here: a plugin that declared its own effective permissions
     *         would be granting them to itself
     */
    public Map<String, Object> requestedPermissions() {
        return requestedPermissions;
    }

    /** @return problems found while parsing, before {@link #validate()} adds structural ones */
    public List<String> problems() {
        return problems;
    }

    /** @return {@code pluginId:version} */
    public String coordinate() {
        return pluginId + ":" + version;
    }

    @Override
    public String toString() {
        return "PluginManifest{" + coordinate() + "}";
    }

    private static Map<String, Object> asStringKeyed(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value).trim();
        return string.isEmpty() ? null : string;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Mutable accumulator, private because a manifest only ever comes from a document. */
    private static final class Builder {
        private String pluginId;
        private String name;
        private String version;
        private String description;
        private String vendor;
        private String mainClass;
        private String sdkVersion;
        private String javaVersion;
        private String engineCompatibility;
        private PluginType pluginType;
        private final List<ManifestNode> nodes = new ArrayList<>();
        private final List<ManifestDependency> dependencies = new ArrayList<>();
        private final Map<String, Object> requestedPermissions = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();

        private PluginManifest build() {
            return new PluginManifest(this);
        }
    }

    /**
     * One node type a plugin contributes, as declared in the manifest.
     *
     * <p>Everything a designer needs to render the node without the plugin being loaded anywhere, which is
     * what lets the marketplace show a plugin's nodes before it is installed.
     *
     * @param nodeType            stable identifier a workflow node references
     * @param displayName         label in the palette
     * @param description         one line of help
     * @param category            palette grouping
     * @param icon                icon name
     * @param configurationSchema JSON schema for the node's configuration
     * @param inputPorts          named inputs, empty for the usual single input
     * @param outputPorts         named outputs, such as decision branches
     */
    public record ManifestNode(
            String nodeType,
            String displayName,
            String description,
            String category,
            String icon,
            Map<String, Object> configurationSchema,
            List<String> inputPorts,
            List<String> outputPorts) {

        public ManifestNode {
            configurationSchema = configurationSchema == null ? Map.of() : Map.copyOf(configurationSchema);
            inputPorts = inputPorts == null ? List.of() : List.copyOf(inputPorts);
            outputPorts = outputPorts == null ? List.of() : List.copyOf(outputPorts);
        }

        static ManifestNode from(Map<String, Object> node, int index, List<String> problems) {
            String nodeType = text(node, "nodeType");
            if (isBlank(nodeType)) {
                problems.add("nodes[" + index + "] has no nodeType.");
                nodeType = "UNKNOWN_" + index;
            } else if (!nodeType.matches("[A-Z][A-Z0-9_]*")) {
                problems.add("nodeType '" + nodeType + "' must be upper-case letters, digits and "
                        + "underscores, so it cannot collide with a built-in type by accident of casing.");
            }
            Object schema = node.get("configurationSchema");
            return new ManifestNode(nodeType,
                    text(node, "displayName"),
                    text(node, "description"),
                    text(node, "category"),
                    text(node, "icon"),
                    schema instanceof Map<?, ?> map ? asStringKeyed(map) : Map.of(),
                    stringList(node.get("inputPorts")),
                    stringList(node.get("outputPorts")));
        }

        private static List<String> stringList(Object value) {
            if (!(value instanceof Collection<?> collection)) {
                return List.of();
            }
            List<String> items = new ArrayList<>();
            for (Object item : collection) {
                if (item != null) {
                    items.add(String.valueOf(item));
                }
            }
            return items;
        }

        /** @return the display name, falling back to the node type */
        public String label() {
            return isBlank(displayName) ? nodeType : displayName;
        }
    }

    /**
     * A third-party library a plugin needs.
     *
     * <p>Recorded for the registry's dependency view and for a future conflict check. The engine does not
     * resolve these: a plugin ships its own dependencies inside its archive, which is what makes two plugins
     * able to use two versions of the same library.
     *
     * @param groupId    Maven group
     * @param artifactId Maven artifact
     * @param version    version, as declared
     * @param scope      {@code bundled} when shaded into the JAR, {@code provided} when expected from the engine
     */
    public record ManifestDependency(String groupId, String artifactId, String version, String scope) {

        static ManifestDependency from(Map<String, Object> map) {
            String scope = text(map, "scope");
            return new ManifestDependency(text(map, "groupId"), text(map, "artifactId"),
                    text(map, "version"), scope == null ? "bundled" : scope);
        }

        /** @return {@code groupId:artifactId:version} */
        public String coordinate() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
