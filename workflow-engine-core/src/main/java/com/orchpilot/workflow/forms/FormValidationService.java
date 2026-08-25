package com.orchpilot.workflow.forms;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a submission against the authoritative form version.
 *
 * <p>Runs on the server for every submission. The Angular renderer applies the same rules while the user
 * types, but that is for their benefit: a submission arriving by {@code curl}, or from a page whose
 * JavaScript was tampered with, is validated identically here.
 *
 * <p>Returns every problem rather than the first. Someone filling a ten-field form should be told all of what
 * is wrong in one response, not discover the rules one rejection at a time.
 */
@Service
public class FormValidationService {

    /** Deliberately permissive: rejecting unusual but legal addresses is worse than accepting a typo. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private static final Pattern URL = Pattern.compile("^https?://\\S+$");

    /**
     * Validates submitted data.
     *
     * @param version  the authoritative form version
     * @param formData the submitted values, keyed by field name
     * @return the problems found, keyed by field name; empty when the submission is acceptable
     */
    public Map<String, List<String>> validate(FormVersion version, Map<String, Object> formData) {
        Map<String, List<String>> problems = new LinkedHashMap<>();
        if (version == null) {
            return problems;
        }
        Map<String, Object> data = formData == null ? Map.of() : formData;

        for (FormField field : version.getFields()) {
            if (!field.collectsValue() || field.getName() == null) {
                continue;
            }
            // A read-only field is not editable, so whatever arrives for it is ignored rather than validated:
            // its value comes from the workflow, not from the user.
            if (field.isReadOnly()) {
                continue;
            }

            List<String> fieldProblems = validateField(field, data.get(field.getName()));
            if (!fieldProblems.isEmpty()) {
                problems.put(field.getName(), fieldProblems);
            }
        }

        // Keys that match no field. Reported rather than ignored: it usually means the form was edited after
        // the page was loaded, and telling the user beats silently discarding their input.
        for (String key : data.keySet()) {
            if (version.fieldByName(key).isEmpty()) {
                problems.computeIfAbsent(key, k -> new ArrayList<>())
                        .add("This form has no field named '" + key + "'");
            }
        }
        return problems;
    }

    private List<String> validateField(FormField field, Object raw) {
        List<String> problems = new ArrayList<>();
        FormValidationRule rule = field.getValidation();
        boolean empty = isEmpty(raw);

        if (rule.isRequired() && empty) {
            problems.add((label(field)) + " is required");
            // No point checking length or pattern on a missing value; the message would be noise.
            return problems;
        }
        if (empty) {
            return problems;
        }

        String text = raw instanceof String string ? string : String.valueOf(raw);

        if (rule.getMinLength() != null && text.length() < rule.getMinLength()) {
            problems.add("Must be at least " + rule.getMinLength() + " characters");
        }
        if (rule.getMaxLength() != null && text.length() > rule.getMaxLength()) {
            problems.add("Must be no more than " + rule.getMaxLength() + " characters");
        }

        switch (field.getType()) {
            case NUMBER -> validateNumber(text, rule, problems);
            case EMAIL -> {
                if (!EMAIL.matcher(text).matches()) {
                    problems.add("Must be a valid email address");
                }
            }
            case URL -> {
                if (!URL.matcher(text).matches()) {
                    problems.add("Must be a URL starting with http:// or https://");
                }
            }
            case DATE -> {
                try {
                    LocalDate.parse(text);
                } catch (DateTimeParseException ex) {
                    problems.add("Must be a date in YYYY-MM-DD format");
                }
            }
            case DROPDOWN, RADIO -> validateOption(field, text, problems);
            case MULTI_SELECT, CHECKBOX_GROUP -> validateOptions(field, raw, problems);
            default -> {
                // Other types have no type-specific rule beyond length and pattern.
            }
        }

        if (rule.getPattern() != null && !rule.getPattern().isBlank()) {
            try {
                if (!Pattern.matches(rule.getPattern(), text)) {
                    problems.add(rule.getPatternMessage() == null || rule.getPatternMessage().isBlank()
                            ? "Is not in the expected format"
                            : rule.getPatternMessage());
                }
            } catch (PatternSyntaxException ex) {
                // A broken regex is a designer error. Reporting it beats accepting everything silently,
                // which would make the constraint appear to work while enforcing nothing.
                problems.add("This field has an invalid validation pattern; contact the form's author");
            }
        }
        return problems;
    }

    private void validateNumber(String text, FormValidationRule rule, List<String> problems) {
        double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            problems.add("Must be a number");
            return;
        }
        if (rule.getMin() != null && value < rule.getMin()) {
            problems.add("Must be at least " + trim(rule.getMin()));
        }
        if (rule.getMax() != null && value > rule.getMax()) {
            problems.add("Must be no more than " + trim(rule.getMax()));
        }
    }

    /**
     * A single choice must be one of the declared options.
     *
     * <p>This is a real control, not tidiness: without it a caller could submit any string for a dropdown,
     * and a later decision node branching on that value would take a path the designer never defined.
     */
    private void validateOption(FormField field, String value, List<String> problems) {
        if (field.getOptions().isEmpty()) {
            return;
        }
        boolean known = field.getOptions().stream()
                .anyMatch(option -> value.equals(option.value()));
        if (!known) {
            problems.add("Must be one of the available options");
        }
    }

    private void validateOptions(FormField field, Object raw, List<String> problems) {
        if (field.getOptions().isEmpty()) {
            return;
        }
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        for (Object value : values) {
            String text = String.valueOf(value);
            boolean known = field.getOptions().stream().anyMatch(option -> text.equals(option.value()));
            if (!known) {
                problems.add("'" + text + "' is not one of the available options");
            }
        }
    }

    private static boolean isEmpty(Object raw) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof String string) {
            return string.trim().isEmpty();
        }
        if (raw instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private static String label(FormField field) {
        return field.getLabel() == null || field.getLabel().isBlank() ? field.getName() : field.getLabel();
    }

    /** Renders 3.0 as "3" so a message reads naturally. */
    private static String trim(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }
}
