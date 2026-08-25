package com.orchpilot.plugin.excel.ops;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks rows against typed rules and reports every failure.
 *
 * <h2>Reports everything, stops at nothing</h2>
 *
 * Validation collects all failures rather than throwing on the first. Someone fixing a spreadsheet wants the
 * whole list, not one error at a time across ten runs — so the node <em>succeeds</em> while reporting
 * {@code valid: false}, and the workflow branches on that. Failing the node would conflate "the check could
 * not run" with "the data is wrong", which are different problems with different responses.
 *
 * <p>Row numbers in the output are 1-based and count the header, so they match what the user sees in Excel.
 * Reporting a zero-based data index would send them to the wrong row.
 */
public final class ValidationEngine {

    /** What a rule checks. */
    public enum RuleType {
        REQUIRED,
        STRING,
        NUMBER,
        INTEGER,
        DECIMAL,
        EMAIL,
        DATE,
        DATE_RANGE,
        NUMBER_RANGE,
        REGEX,
        ALLOWED_VALUES;

        static RuleType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw ExcelException.invalidData("Every validation rule needs a 'type'.");
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            for (RuleType type : values()) {
                if (type.name().equals(normalised)) {
                    return type;
                }
            }
            throw ExcelException.invalidData("Unknown validation rule '" + raw + "'.");
        }
    }

    /**
     * A deliberately pragmatic address check.
     *
     * <p>Fully validating an address per RFC 5322 needs a parser, and the resulting regex is famously
     * unreadable and still wrong at the edges. This catches what a spreadsheet actually gets wrong — a missing
     * {@code @}, a missing domain, spaces — and does not pretend to more.
     */
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");

    /** One failure. */
    public record Failure(int row, String column, String value, String message) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("row", row);
            map.put("column", column);
            map.put("value", value);
            map.put("message", message);
            return map;
        }
    }

    /** A rule, parsed and ready to apply. */
    private record Rule(String column, RuleType type, boolean required, BigDecimal min, BigDecimal max,
                        LocalDate after, LocalDate before, Pattern pattern, List<String> allowed,
                        String message) {
    }

    private ValidationEngine() {
    }

    /**
     * Validates a table.
     *
     * @param rules     the configured rules
     * @param maxErrors stop collecting after this many, so a wholly wrong file does not produce a million
     *                  entries that no one will read and that would not fit in a workflow variable
     * @return every failure found, in row then rule order
     */
    public static List<Failure> validate(SheetTable table, List<Map<String, Object>> rules, int maxErrors) {
        List<Rule> parsed = parse(rules, table);
        List<Failure> failures = new ArrayList<>();
        if (parsed.isEmpty()) {
            return failures;
        }

        List<List<CellValue>> rows = table.rows();
        for (int i = 0; i < rows.size() && failures.size() < maxErrors; i++) {
            List<CellValue> row = rows.get(i);
            // +2: one for the header row, one to make it 1-based, so this matches Excel's own numbering.
            int displayRow = i + 2;
            for (Rule rule : parsed) {
                if (failures.size() >= maxErrors) {
                    break;
                }
                CellValue cell = table.cell(row, rule.column());
                String problem = check(cell, rule);
                if (problem != null) {
                    failures.add(new Failure(displayRow, rule.column(), cell.asText(),
                            rule.message() != null ? rule.message() : problem));
                }
            }
        }
        return failures;
    }

    /** @return a description of the problem, or null when the cell passes */
    private static String check(CellValue cell, Rule rule) {
        if (cell.isBlank()) {
            // Only REQUIRED cares about an empty cell. Every other rule describes the shape a value must have
            // if present, so applying it to a blank would force authors to mark every optional column.
            return rule.type() == RuleType.REQUIRED || rule.required()
                    ? "A value is required." : null;
        }
        String text = cell.asText().trim();

        return switch (rule.type()) {
            case REQUIRED -> null;
            case STRING -> null;
            case NUMBER, DECIMAL -> cell.asNumber() == null ? "'" + text + "' is not a number." : null;
            case INTEGER -> integerProblem(cell, text);
            case EMAIL -> EMAIL.matcher(text).matches() ? null : "'" + text + "' is not a valid email address.";
            case DATE -> temporal(text) == null ? "'" + text + "' is not a valid date." : null;
            case NUMBER_RANGE -> rangeProblem(cell, rule, text);
            case DATE_RANGE -> dateRangeProblem(text, rule);
            case REGEX -> rule.pattern() != null && rule.pattern().matcher(text).matches()
                    ? null : "'" + text + "' does not match the required pattern.";
            case ALLOWED_VALUES -> allowedProblem(text, rule);
        };
    }

    private static String integerProblem(CellValue cell, String text) {
        BigDecimal number = cell.asNumber();
        if (number == null) {
            return "'" + text + "' is not a number.";
        }
        return number.stripTrailingZeros().scale() <= 0 ? null : "'" + text + "' is not a whole number.";
    }

    private static String rangeProblem(CellValue cell, Rule rule, String text) {
        BigDecimal number = cell.asNumber();
        if (number == null) {
            return "'" + text + "' is not a number.";
        }
        if (rule.min() != null && number.compareTo(rule.min()) < 0) {
            return text + " is below the minimum of " + rule.min().toPlainString() + ".";
        }
        if (rule.max() != null && number.compareTo(rule.max()) > 0) {
            return text + " is above the maximum of " + rule.max().toPlainString() + ".";
        }
        return null;
    }

    private static String dateRangeProblem(String text, Rule rule) {
        LocalDate date = temporal(text);
        if (date == null) {
            return "'" + text + "' is not a valid date.";
        }
        if (rule.after() != null && date.isBefore(rule.after())) {
            return text + " is before " + rule.after() + ".";
        }
        if (rule.before() != null && date.isAfter(rule.before())) {
            return text + " is after " + rule.before() + ".";
        }
        return null;
    }

    private static String allowedProblem(String text, Rule rule) {
        if (rule.allowed() == null || rule.allowed().isEmpty()) {
            return null;
        }
        for (String candidate : rule.allowed()) {
            if (candidate.equalsIgnoreCase(text)) {
                return null;
            }
        }
        return "'" + text + "' is not one of: " + String.join(", ", rule.allowed()) + ".";
    }

    /** Accepts both a plain date and a date-time, since a cell may hold either. */
    private static LocalDate temporal(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            try {
                return LocalDateTime.parse(text).toLocalDate();
            } catch (DateTimeParseException nested) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Rule> parse(List<Map<String, Object>> raw, SheetTable table) {
        List<Rule> rules = new ArrayList<>();
        if (raw == null) {
            return rules;
        }
        for (Map<String, Object> entry : raw) {
            String column = entry.get("column") == null ? null : String.valueOf(entry.get("column"));
            if (column == null || column.isBlank()) {
                throw ExcelException.invalidData("Every validation rule needs a 'column'.");
            }
            if (table.columnIndex(column) < 0) {
                throw ExcelException.columnNotFound(column, table.headers());
            }
            RuleType type = RuleType.parse(entry.get("type") == null ? null
                    : String.valueOf(entry.get("type")));

            List<String> allowed = null;
            if (entry.get("allowedValues") instanceof List<?> list) {
                allowed = new ArrayList<>();
                for (Object value : list) {
                    allowed.add(String.valueOf(value));
                }
            }
            Pattern pattern = null;
            if (type == RuleType.REGEX) {
                pattern = compile(entry.get("pattern"));
            }
            rules.add(new Rule(column, type, Boolean.TRUE.equals(entry.get("required")),
                    number(entry.get("min")), number(entry.get("max")),
                    date(entry.get("after")), date(entry.get("before")),
                    pattern, allowed,
                    entry.get("message") == null ? null : String.valueOf(entry.get("message"))));
        }
        return rules;
    }

    private static Pattern compile(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw ExcelException.invalidData("A REGEX rule needs a 'pattern'.");
        }
        try {
            return Pattern.compile(String.valueOf(raw));
        } catch (PatternSyntaxException ex) {
            throw ExcelException.invalidData("The validation pattern is not valid: " + ex.getDescription());
        }
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate date(Object value) {
        return value == null ? null : temporal(String.valueOf(value).trim());
    }
}
