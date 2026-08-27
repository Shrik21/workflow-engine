package com.orchpilot.workflow.ai.node;

import java.util.List;
import java.util.Map;

/**
 * Checks a model's structured output against the node's JSON schema, and explains every way it falls short.
 *
 * <h2>Why this is more than "is it a map"</h2>
 *
 * A model asked for JSON usually returns JSON — but not always the <em>right</em> JSON: a required field missing,
 * a number where a string was asked for, a status outside the allowed set. Catching those precisely is what makes
 * the repair re-prompt in {@link AIAgentNodeExecutor} work: the executor hands the model back the exact list of
 * problems this produces, so the model can correct itself rather than the workflow failing on a near-miss. The
 * check is deliberately a pragmatic subset of JSON Schema — object shape, required properties, top-level property
 * types and {@code enum} membership — which covers what a workflow author actually declares, without pulling in a
 * full schema engine.
 */
final class StructuredOutputValidator {

    private StructuredOutputValidator() {
    }

    /**
     * @param value  the parsed model output (a map for a valid object, or null/other when the model did not
     *               return an object at all)
     * @param schema the node's JSON schema; an empty schema accepts any object
     * @return the problems found, in reading order; empty when the output satisfies the schema
     */
    @SuppressWarnings("unchecked")
    static List<String> validate(Object value, Map<String, Object> schema) {
        List<String> problems = new java.util.ArrayList<>();
        if (!(value instanceof Map)) {
            problems.add("the output is not a JSON object");
            return problems;
        }
        if (schema == null || schema.isEmpty()) {
            return problems;
        }
        Map<String, Object> object = (Map<String, Object>) value;

        Object required = schema.get("required");
        if (required instanceof List<?> names) {
            for (Object name : names) {
                if (!object.containsKey(String.valueOf(name))) {
                    problems.add("missing required property '" + name + "'");
                }
            }
        }

        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!object.containsKey(name) || !(entry.getValue() instanceof Map<?, ?> definition)) {
                    continue;
                }
                checkProperty(name, object.get(name), (Map<String, Object>) definition, problems);
            }
        }
        return problems;
    }

    private static void checkProperty(String name, Object actual, Map<String, Object> definition,
                                      List<String> problems) {
        // A null for a declared-but-not-required property is acceptable; only a present, wrong value is a problem.
        if (actual == null) {
            return;
        }
        Object type = definition.get("type");
        if (type != null && !matchesType(actual, String.valueOf(type))) {
            problems.add("property '" + name + "' should be " + type + " but was "
                    + jsonType(actual));
        }
        Object enumValues = definition.get("enum");
        if (enumValues instanceof List<?> allowed && !allowed.isEmpty()) {
            boolean permitted = allowed.stream().anyMatch(candidate -> String.valueOf(candidate)
                    .equals(String.valueOf(actual)));
            if (!permitted) {
                problems.add("property '" + name + "' must be one of " + allowed + " but was '" + actual + "'");
            }
        }
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "string" -> value instanceof CharSequence;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()));
            case "number" -> value instanceof Number;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true; // Unknown/absent type declarations are not enforced.
        };
    }

    private static String jsonType(Object value) {
        if (value instanceof CharSequence) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return "null";
    }
}
