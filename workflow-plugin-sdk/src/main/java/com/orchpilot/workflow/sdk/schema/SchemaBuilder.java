package com.orchpilot.workflow.sdk.schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small fluent helper for producing the JSON-schema-shaped configuration description a plugin
 * publishes in {@link com.orchpilot.workflow.sdk.node.NodeDefinition#configurationSchema()}.
 *
 * <p>Exists so plugin authors do not hand-assemble nested {@code Map} literals, and so every plugin
 * emits the same schema dialect for the front end to render. Output is a plain {@code Map}, keeping
 * the SDK free of a JSON library.
 *
 * <pre>{@code
 * Map<String, Object> schema = SchemaBuilder.object()
 *         .string("to", "Recipient", true)
 *         .string("subject", "Subject", true)
 *         .text("body", "Body", true)
 *         .select("priority", "Priority", List.of("LOW", "NORMAL", "HIGH"), false)
 *         .integer("timeout", "Timeout (ms)", false)
 *         .secretRef("apiKeySecret", "API key secret name", true)
 *         .build();
 * }</pre>
 *
 * @since 1.0.0
 */
public final class SchemaBuilder {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private SchemaBuilder() {
    }

    /**
     * @return a builder for an object schema
     */
    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    /**
     * Single-line text field.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder string(String name, String title, boolean isRequired) {
        return property(name, title, isRequired, entry("type", "string"));
    }

    /**
     * Multi-line text field, rendered as a textarea.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder text(String name, String title, boolean isRequired) {
        return property(name, title, isRequired, entry("type", "string"), entry("format", "textarea"));
    }

    /**
     * Integer field.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder integer(String name, String title, boolean isRequired) {
        return property(name, title, isRequired, entry("type", "integer"));
    }

    /**
     * Boolean field, rendered as a checkbox.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder bool(String name, String title, boolean isRequired) {
        return property(name, title, isRequired, entry("type", "boolean"));
    }

    /**
     * Free-form key/value map, rendered as an editable table.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder map(String name, String title, boolean isRequired) {
        return property(name, title, isRequired,
                entry("type", "object"),
                entry("additionalProperties", Map.of("type", "string")));
    }

    /**
     * Nested object described by another schema.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param nested     schema produced by another {@link SchemaBuilder}
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder object(String name, String title, Map<String, Object> nested, boolean isRequired) {
        Map<String, Object> node = new LinkedHashMap<>(nested == null ? Map.of() : nested);
        node.put("title", title);
        properties.put(name, node);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    /**
     * Enumerated single-choice field, rendered as a dropdown.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param options    permitted values
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder select(String name, String title, List<String> options, boolean isRequired) {
        return property(name, title, isRequired,
                entry("type", "string"),
                entry("enum", options == null ? List.of() : List.copyOf(options)));
    }

    /**
     * Reference to a secret by name. Renders as a secret picker and makes explicit that the value
     * stored in the workflow is a name, never a credential.
     *
     * @param name       property name
     * @param title      label shown in the property panel
     * @param isRequired whether the engine rejects a node without this value
     * @return this builder
     */
    public SchemaBuilder secretRef(String name, String title, boolean isRequired) {
        return property(name, title, isRequired,
                entry("type", "string"),
                entry("format", "secret-ref"));
    }

    /**
     * Adds a default value to the most recently declared property.
     *
     * @param name         property name
     * @param defaultValue value the designer pre-fills
     * @return this builder
     * @throws IllegalArgumentException when {@code name} was not declared
     */
    @SuppressWarnings("unchecked")
    public SchemaBuilder withDefault(String name, Object defaultValue) {
        Object node = properties.get(name);
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException("Unknown schema property: " + name);
        }
        ((Map<String, Object>) node).put("default", defaultValue);
        return this;
    }

    /**
     * Adds help text to a declared property.
     *
     * @param name        property name
     * @param description help text
     * @return this builder
     * @throws IllegalArgumentException when {@code name} was not declared
     */
    @SuppressWarnings("unchecked")
    public SchemaBuilder withDescription(String name, String description) {
        Object node = properties.get(name);
        if (!(node instanceof Map)) {
            throw new IllegalArgumentException("Unknown schema property: " + name);
        }
        ((Map<String, Object>) node).put("description", description);
        return this;
    }

    /**
     * Marks properties as advanced, so the designer renders them behind a "show advanced" toggle.
     *
     * <p>For settings that apply but have a sensible default and are almost never changed — row limits,
     * timeouts, protocol overrides. It is a different statement from a visibility condition: that one says a
     * field <em>does not apply</em> to the chosen operation, this one says it applies and is already right.
     *
     * <p>An operation whose form has three fields worth setting and eight that are correct as they stand is
     * tiring to use when all eleven render flat, and that is the common shape once a node declares its
     * connection, its target and its limits.
     *
     * <p>A required property is never hidden, whatever is passed here: the designer ignores the flag for
     * anything in {@code required}, because hiding a field the node cannot run without is how an author ends
     * up staring at a validation error for a control they were never shown.
     *
     * @param names properties to mark; unknown names are ignored rather than throwing, so a shared helper can
     *              mark a set of fields without knowing which of them this particular operation declared
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public SchemaBuilder advanced(String... names) {
        for (String name : names) {
            Object node = properties.get(name);
            if (node instanceof Map) {
                ((Map<String, Object>) node).put("advanced", true);
            }
        }
        return this;
    }

    /**
     * Makes a property required only for certain values of another one.
     *
     * <p>Fills the gap that forces a plugin with an operation selector to declare nothing required at all.
     * The schema's {@code required} list is unconditional — the engine refuses any node missing one of those
     * entries — so a field that applies to a single operation cannot go in it without breaking every node
     * that chose a different operation. The alternative plugins reach for is to require nothing and check at
     * execution instead, which moves the error from publish time to run time, where it costs an execution.
     *
     * <p>Evaluated by the designer and, authoritatively, by the engine's workflow validator. Pairs naturally
     * with {@link #visibleWhen}: a field that only shows for {@code AGGREGATE} is usually also only required
     * for it.
     *
     * @param name      the property that becomes conditionally required
     * @param dependsOn the property whose value decides
     * @param values    the values of {@code dependsOn} that make it required
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public SchemaBuilder requiredWhen(String name, String dependsOn, List<String> values) {
        Object node = properties.get(name);
        if (node instanceof Map) {
            ((Map<String, Object>) node).put("requiredWhen", Map.of(dependsOn, List.copyOf(values)));
        }
        return this;
    }

    /**
     * Shows a property only for certain values of another one.
     *
     * <p>Presentation only, and deliberately so: a hidden field whose value is set anyway is still submitted
     * and still validated. Use {@link #requiredWhen} for the enforcement half.
     *
     * @param name      the property to condition
     * @param dependsOn the property whose value decides
     * @param values    the values of {@code dependsOn} that show it
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public SchemaBuilder visibleWhen(String name, String dependsOn, List<String> values) {
        Object node = properties.get(name);
        if (node instanceof Map) {
            ((Map<String, Object>) node).put("visibleWhen", Map.of(dependsOn, List.copyOf(values)));
        }
        return this;
    }

    /**
     * Adds a sentence of help per option of an enumerated property.
     *
     * <p>An operation selector with forty entries is a list of names the author has to already know.
     * Carrying a description per option is what turns it into something choosable — the designer shows the
     * one belonging to the current selection.
     *
     * @param name         the enumerated property
     * @param descriptions option value to help text; entries naming an option that does not exist are kept
     *                     and simply never shown, so the two lists need not be maintained in lockstep
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    public SchemaBuilder describeOptions(String name, Map<String, String> descriptions) {
        Object node = properties.get(name);
        if (node instanceof Map) {
            ((Map<String, Object>) node).put("enumDescriptions", Map.copyOf(descriptions));
        }
        return this;
    }

    /**
     * @return an immutable schema map with {@code type}, {@code properties} and {@code required}
     */
    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.copyOf(properties));
        schema.put("required", List.copyOf(required));
        return Map.copyOf(schema);
    }

    @SafeVarargs
    private SchemaBuilder property(String name, String title, boolean isRequired, Map.Entry<String, Object>... attrs) {
        Map<String, Object> node = new LinkedHashMap<>();
        for (Map.Entry<String, Object> attr : Arrays.asList(attrs)) {
            node.put(attr.getKey(), attr.getValue());
        }
        node.put("title", title);
        properties.put(name, node);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
