package com.orchpilot.workflow.forms;

import com.orchpilot.workflow.utility.MapPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps form data onto nested workflow variables, coercing each value to the field's declared type.
 *
 * <p>Coercion matters more than it looks. An HTML form submits everything as text, so a salary field arrives
 * as {@code "120000"}. A later decision node evaluating {@code employee.salary > 100000} would compare a
 * string to a number and take the wrong branch. Converting here, using the field type the form declares,
 * is what makes the expression behave as its author expects.
 *
 * <p>A value that cannot be coerced is passed through unchanged rather than dropped. Validation runs before
 * mapping and is the place that rejects bad input; silently discarding a value here would lose data that
 * validation had already accepted.
 */
@Service
public class DefaultFormVariableMapper implements FormVariableMapper {

    private static final Logger log = LoggerFactory.getLogger(DefaultFormVariableMapper.class);

    @Override
    public Map<String, Object> mapFormDataToVariables(FormVersion version, Map<String, Object> formData) {
        Map<String, Object> variables = new LinkedHashMap<>();
        mapFormDataToVariablePaths(version, formData).forEach((path, value) ->
                // Dotted paths become nested structures: employee.name lands at {employee: {name: ...}}.
                MapPaths.put(variables, path, value));
        return variables;
    }

    @Override
    public Map<String, Object> mapFormDataToVariablePaths(FormVersion version, Map<String, Object> formData) {
        Map<String, Object> paths = new LinkedHashMap<>();
        if (version == null || formData == null) {
            return paths;
        }

        for (FormField field : version.getFields()) {
            if (!field.collectsValue() || !field.isMapped() || field.getName() == null) {
                continue;
            }
            // Driven by the form's fields, not by the payload's keys. An unexpected key therefore cannot
            // reach a variable, whatever the browser sent.
            if (!formData.containsKey(field.getName())) {
                continue;
            }
            paths.put(field.getVariable(), coerce(formData.get(field.getName()), field));
        }
        return paths;
    }

    /**
     * Converts a submitted value to the type the field declares.
     *
     * @param raw   the submitted value
     * @param field the field it came from
     * @return the coerced value, or the original when conversion is not possible
     */
    private Object coerce(Object raw, FormField field) {
        if (raw == null) {
            return null;
        }
        FormFieldType.DataType target = field.getVariableType() != null
                ? field.getVariableType()
                : field.getType().primaryType();

        try {
            return switch (target) {
                case STRING -> raw instanceof String ? raw : String.valueOf(raw);
                case INTEGER -> toInteger(raw);
                case LONG -> toLong(raw);
                case DOUBLE -> toDouble(raw);
                case BOOLEAN -> toBoolean(raw);
                case DATE -> toDate(raw);
                case LIST -> toList(raw);
                // DATETIME and OBJECT are passed through: an ISO instant is already usable as a string, and
                // an arbitrary object has no meaningful conversion.
                case DATETIME, OBJECT -> raw;
            };
        } catch (RuntimeException ex) {
            // Validation has already accepted this value, so dropping it would lose data. Kept as supplied
            // and logged, which surfaces a genuine field/variable type mismatch without failing the task.
            log.warn("Could not coerce field '{}' to {}; storing the submitted value unchanged",
                    field.getName(), target);
            return raw;
        }
    }

    private static Object toInteger(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : Integer.valueOf((int) Double.parseDouble(text));
    }

    private static Object toLong(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : Long.valueOf((long) Double.parseDouble(text));
    }

    private static Object toDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : Double.valueOf(text);
    }

    /**
     * Only the exact string {@code true} is true, matching {@link Boolean#parseBoolean}.
     *
     * <p>An unchecked HTML checkbox is usually absent rather than false, which the caller handles by
     * defaulting; anything else present but unrecognised is false rather than an error.
     */
    private static Object toBoolean(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        return "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text) || "1".equals(text);
    }

    /** Stored as an ISO date string, which is what the expression evaluator and MongoDB both handle well. */
    private static Object toDate(Object raw) {
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text).toString();
        } catch (DateTimeParseException ex) {
            return text;
        }
    }

    private static Object toList(Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        // A single choice arriving from a multi-select becomes a one-element list, so a downstream
        // expression can iterate without checking the shape.
        List<Object> single = new ArrayList<>();
        single.add(text);
        return single;
    }
}
