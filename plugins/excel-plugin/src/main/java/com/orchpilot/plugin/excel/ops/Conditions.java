package com.orchpilot.plugin.excel.ops;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Row matching: the one engine behind both search and filter.
 *
 * <h2>Search is a filter with a single condition</h2>
 *
 * They are presented as separate operations because that is how people think about them — "find the IT rows"
 * and "rows where department is IT and salary is over 50 000" feel different. They are not different
 * mechanically, and implementing them twice would mean two places where {@code CONTAINS} could disagree about
 * case sensitivity. So there is one evaluator, and search builds a one-element condition list.
 *
 * <h2>Regex is off unless asked for</h2>
 *
 * A pattern is compiled from workflow input, and a careless one backtracks catastrophically — a few dozen
 * characters can hang a thread for the age of the universe. So regex is opt-in per node, the pattern is
 * compiled once rather than per row, and an invalid one fails the node with a clear message instead of
 * throwing from inside a loop.
 */
public final class Conditions {

    /** How a single condition compares a cell to a value. */
    public enum Operator {
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        LESS_THAN,
        GREATER_OR_EQUAL,
        LESS_OR_EQUAL,
        CONTAINS,
        NOT_CONTAINS,
        STARTS_WITH,
        ENDS_WITH,
        BETWEEN,
        IS_EMPTY,
        IS_NOT_EMPTY,
        REGEX;

        static Operator parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return EQUALS;
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            for (Operator operator : values()) {
                if (operator.name().equals(normalised)) {
                    return operator;
                }
            }
            throw ExcelException.invalidData("Unknown operator '" + raw + "'.");
        }
    }

    /** Whether every condition must match, or any one of them. */
    public enum Combine {
        AND,
        OR;

        static Combine parse(String raw) {
            return raw != null && "OR".equalsIgnoreCase(raw.trim()) ? OR : AND;
        }
    }

    /**
     * One condition.
     *
     * @param column        the column to test
     * @param operator      how to compare
     * @param value         the comparison value; the lower bound for {@link Operator#BETWEEN}
     * @param secondValue   the upper bound, {@link Operator#BETWEEN} only
     * @param caseSensitive whether text comparisons respect case
     * @param pattern       pre-compiled regex, {@link Operator#REGEX} only
     */
    public record Condition(String column, Operator operator, String value, String secondValue,
                            boolean caseSensitive, Pattern pattern) {
    }

    private Conditions() {
    }

    /**
     * Builds conditions from node configuration.
     *
     * @param raw         the configured condition list
     * @param allowRegex  whether {@link Operator#REGEX} may be used
     * @throws ExcelException when a condition is malformed or uses regex without it being enabled
     */
    public static List<Condition> parse(List<Map<String, Object>> raw, boolean allowRegex) {
        List<Condition> conditions = new ArrayList<>();
        if (raw == null) {
            return conditions;
        }
        for (Map<String, Object> entry : raw) {
            String column = text(entry.get("column"));
            if (column == null || column.isBlank()) {
                throw ExcelException.invalidData("Every condition needs a 'column'.");
            }
            Operator operator = Operator.parse(text(entry.get("operator")));
            boolean caseSensitive = Boolean.TRUE.equals(entry.get("caseSensitive"));
            String value = text(entry.get("value"));

            Pattern pattern = null;
            if (operator == Operator.REGEX) {
                if (!allowRegex) {
                    throw ExcelException.invalidData("Regex matching is not enabled on this node. Turn on "
                            + "'allowRegex' to use it.");
                }
                pattern = compile(value, caseSensitive);
            }
            conditions.add(new Condition(column, operator, value, text(entry.get("secondValue")),
                    caseSensitive, pattern));
        }
        return conditions;
    }

    /** Compiles once, so a pathological pattern costs one failure rather than one per row. */
    private static Pattern compile(String regex, boolean caseSensitive) {
        if (regex == null || regex.isBlank()) {
            throw ExcelException.invalidData("A REGEX condition needs a pattern in 'value'.");
        }
        try {
            return Pattern.compile(regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException ex) {
            throw ExcelException.invalidData("The regular expression is not valid: " + ex.getDescription());
        }
    }

    /**
     * Applies conditions to a table.
     *
     * @throws ExcelException when a condition names a column the sheet does not have
     */
    public static SheetTable apply(SheetTable table, List<Condition> conditions, Combine combine) {
        if (conditions.isEmpty()) {
            return table;
        }
        // Resolved once, before the loop: a mistyped column name should fail immediately rather than after
        // scanning a hundred thousand rows to match nothing.
        int[] indexes = new int[conditions.size()];
        for (int i = 0; i < conditions.size(); i++) {
            int index = table.columnIndex(conditions.get(i).column());
            if (index < 0) {
                throw ExcelException.columnNotFound(conditions.get(i).column(), table.headers());
            }
            indexes[i] = index;
        }

        List<List<CellValue>> matched = new ArrayList<>();
        for (List<CellValue> row : table.rows()) {
            if (matches(table, row, conditions, indexes, combine)) {
                matched.add(row);
            }
        }
        return table.withRows(matched);
    }

    /** @return whether one row satisfies the conditions under the given combination */
    public static boolean matches(SheetTable table, List<CellValue> row, List<Condition> conditions,
                                  int[] indexes, Combine combine) {
        for (int i = 0; i < conditions.size(); i++) {
            boolean result = test(table.cell(row, indexes[i]), conditions.get(i));
            if (combine == Combine.AND && !result) {
                return false;
            }
            if (combine == Combine.OR && result) {
                return true;
            }
        }
        // Falling out of an AND means everything matched; out of an OR means nothing did.
        return combine == Combine.AND;
    }

    /** Evaluates one condition against one cell. */
    public static boolean test(CellValue cell, Condition condition) {
        return switch (condition.operator()) {
            case IS_EMPTY -> cell.isBlank();
            case IS_NOT_EMPTY -> !cell.isBlank();
            case EQUALS -> compare(cell, condition.value(), condition.caseSensitive()) == 0;
            case NOT_EQUALS -> compare(cell, condition.value(), condition.caseSensitive()) != 0;
            case GREATER_THAN -> compare(cell, condition.value(), condition.caseSensitive()) > 0;
            case LESS_THAN -> compare(cell, condition.value(), condition.caseSensitive()) < 0;
            case GREATER_OR_EQUAL -> compare(cell, condition.value(), condition.caseSensitive()) >= 0;
            case LESS_OR_EQUAL -> compare(cell, condition.value(), condition.caseSensitive()) <= 0;
            case CONTAINS -> text(cell, condition).contains(needle(condition));
            case NOT_CONTAINS -> !text(cell, condition).contains(needle(condition));
            case STARTS_WITH -> text(cell, condition).startsWith(needle(condition));
            case ENDS_WITH -> text(cell, condition).endsWith(needle(condition));
            case REGEX -> condition.pattern() != null && condition.pattern().matcher(cell.asText()).find();
            case BETWEEN -> between(cell, condition);
        };
    }

    /**
     * Compares a cell to a configured value.
     *
     * <p>Numerically when both sides look like numbers, so {@code Salary > 50000} works on a column Excel
     * stored as text; textually otherwise. Comparing "9" and "10" as text would put 10 first, which is not
     * what any workflow author means.
     */
    private static int compare(CellValue cell, String value, boolean caseSensitive) {
        if (value == null) {
            return cell.isBlank() ? 0 : 1;
        }
        BigDecimal cellNumber = cell.asNumber();
        BigDecimal valueNumber = parseNumber(value);
        if (cellNumber != null && valueNumber != null) {
            return cellNumber.compareTo(valueNumber);
        }
        String left = cell.asText();
        return caseSensitive ? left.compareTo(value) : left.compareToIgnoreCase(value);
    }

    private static boolean between(CellValue cell, Condition condition) {
        if (condition.value() == null || condition.secondValue() == null) {
            throw ExcelException.invalidData("A BETWEEN condition needs both 'value' and 'secondValue'.");
        }
        return compare(cell, condition.value(), condition.caseSensitive()) >= 0
                && compare(cell, condition.secondValue(), condition.caseSensitive()) <= 0;
    }

    private static String text(CellValue cell, Condition condition) {
        String value = cell.asText();
        return condition.caseSensitive() ? value : value.toLowerCase(Locale.ROOT);
    }

    private static String needle(Condition condition) {
        String value = condition.value() == null ? "" : condition.value();
        return condition.caseSensitive() ? value : value.toLowerCase(Locale.ROOT);
    }

    private static BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
