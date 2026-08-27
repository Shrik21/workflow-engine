package com.orchpilot.plugin.excel.ops;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellType;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reshapes a table: renames, maps, adds, removes and converts columns.
 *
 * <h2>A fixed set of steps, not an expression language</h2>
 *
 * A general expression evaluator here would be the obvious design and the wrong one: it would mean shipping an
 * interpreter that runs workflow-supplied text inside the engine's process, which is precisely the capability
 * this platform withholds from plugins everywhere else. So transformation is a list of declared steps, each
 * doing one named thing. {@code CONCAT} joins named columns; it cannot call a method. The cost is that some
 * exotic derivation is not expressible; the benefit is that nothing here can execute anything.
 *
 * <p>Steps apply in order, so a rename followed by a concat referring to the new name works — which is what an
 * author naturally writes.
 */
public final class TransformEngine {

    /** What a single step does. */
    public enum StepType {
        RENAME,
        /** Bulk rename from a source-to-target map — the column-mapping case. */
        MAP,
        REMOVE,
        /** Adds a column holding a fixed value. */
        ADD,
        /** Joins several columns with a separator into a new one. */
        CONCAT,
        /** Re-reads a column as a given type. */
        CONVERT,
        TRIM,
        UPPERCASE,
        LOWERCASE,
        REPLACE,
        FORMAT_DATE,
        /** Arithmetic on one column and a constant. */
        CALCULATE;

        static StepType parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw ExcelException.invalidData("Every transform step needs a 'type'.");
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT);
            for (StepType type : values()) {
                if (type.name().equals(normalised)) {
                    return type;
                }
            }
            throw ExcelException.invalidData("Unknown transform step '" + raw + "'.");
        }
    }

    private TransformEngine() {
    }

    /**
     * Applies every step in order.
     *
     * @param steps the configured steps
     * @return a new table; the input is not modified
     */
    @SuppressWarnings("unchecked")
    public static SheetTable apply(SheetTable table, List<Map<String, Object>> steps) {
        if (steps == null || steps.isEmpty()) {
            return table;
        }
        SheetTable working = copy(table);
        for (Map<String, Object> step : steps) {
            StepType type = StepType.parse(text(step.get("type")));
            switch (type) {
                case RENAME -> rename(working, required(step, "from"), required(step, "to"));
                case MAP -> {
                    Object mapping = step.get("mapping");
                    if (!(mapping instanceof Map<?, ?> map)) {
                        throw ExcelException.invalidData("A MAP step needs a 'mapping' object.");
                    }
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        // Silently ignores a source column that is absent: a mapping is usually written
                        // against a template, and one missing column should not fail the whole transform.
                        working.renameColumn(String.valueOf(entry.getKey()),
                                String.valueOf(entry.getValue()));
                    }
                }
                case REMOVE -> {
                    if (!working.removeColumn(required(step, "column"))) {
                        throw ExcelException.columnNotFound(required(step, "column"), working.headers());
                    }
                }
                case ADD -> addConstant(working, required(step, "column"), text(step.get("value")));
                case CONCAT -> concat(working, step);
                case CONVERT -> convert(working, required(step, "column"),
                        text(step.get("to")), text(step.get("format")));
                case TRIM -> mapColumn(working, required(step, "column"),
                        cell -> CellValue.of(cell.asText().trim()));
                case UPPERCASE -> mapColumn(working, required(step, "column"),
                        cell -> CellValue.of(cell.asText().toUpperCase(Locale.ROOT)));
                case LOWERCASE -> mapColumn(working, required(step, "column"),
                        cell -> CellValue.of(cell.asText().toLowerCase(Locale.ROOT)));
                case REPLACE -> {
                    String search = text(step.get("search"));
                    String replacement = text(step.get("replacement"));
                    mapColumn(working, required(step, "column"), cell -> CellValue.of(
                            cell.asText().replace(search == null ? "" : search,
                                    replacement == null ? "" : replacement)));
                }
                case FORMAT_DATE -> formatDate(working, required(step, "column"),
                        text(step.get("format")));
                case CALCULATE -> calculate(working, step);
            }
        }
        return working;
    }

    // ------------------------------------------------------------------ steps

    private static void rename(SheetTable table, String from, String to) {
        if (!table.renameColumn(from, to)) {
            throw ExcelException.columnNotFound(from, table.headers());
        }
    }

    private static void addConstant(SheetTable table, String column, String value) {
        table.addColumn(column);
        int index = table.columnIndex(column);
        CellValue constant = value == null ? CellValue.BLANK : CellValue.of(value);
        for (List<CellValue> row : table.rows()) {
            table.padTo(row, index + 1);
            row.set(index, constant);
        }
    }

    /** {@code FirstName + LastName → FullName}. */
    @SuppressWarnings("unchecked")
    private static void concat(SheetTable table, Map<String, Object> step) {
        Object sources = step.get("columns");
        if (!(sources instanceof List<?> list) || list.isEmpty()) {
            throw ExcelException.invalidData("A CONCAT step needs a 'columns' array.");
        }
        String target = required(step, "target");
        String separator = step.get("separator") == null ? " " : String.valueOf(step.get("separator"));

        List<String> columns = new ArrayList<>();
        for (Object source : list) {
            String name = String.valueOf(source);
            if (table.columnIndex(name) < 0) {
                throw ExcelException.columnNotFound(name, table.headers());
            }
            columns.add(name);
        }

        table.addColumn(target);
        int targetIndex = table.columnIndex(target);
        for (List<CellValue> row : table.rows()) {
            StringBuilder joined = new StringBuilder();
            for (String column : columns) {
                String part = table.cell(row, column).asText();
                if (part.isEmpty()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append(separator);
                }
                joined.append(part);
            }
            table.padTo(row, targetIndex + 1);
            row.set(targetIndex, CellValue.of(joined.toString()));
        }
    }

    /** {@code Salary * 12 → AnnualSalary}. */
    private static void calculate(SheetTable table, Map<String, Object> step) {
        String source = required(step, "column");
        String target = step.get("target") == null ? source : String.valueOf(step.get("target"));
        String operator = text(step.get("operator"));
        BigDecimal operand = number(step.get("operand"));

        if (table.columnIndex(source) < 0) {
            throw ExcelException.columnNotFound(source, table.headers());
        }
        if (operand == null) {
            throw ExcelException.invalidData("A CALCULATE step needs a numeric 'operand'.");
        }

        if (table.columnIndex(target) < 0) {
            table.addColumn(target);
        }
        int sourceIndex = table.columnIndex(source);
        int targetIndex = table.columnIndex(target);

        for (List<CellValue> row : table.rows()) {
            BigDecimal value = table.cell(row, sourceIndex).asNumber();
            table.padTo(row, targetIndex + 1);
            if (value == null) {
                // A non-numeric cell yields blank rather than zero: zero would be a fabricated number that
                // then flows into a SUM and quietly changes the answer.
                row.set(targetIndex, CellValue.BLANK);
                continue;
            }
            // computed(), not of(): a whole result must render as 600000, not 600000.0.
            row.set(targetIndex, CellValue.computed(arithmetic(value, operator, operand)));
        }
    }

    private static BigDecimal arithmetic(BigDecimal value, String operator, BigDecimal operand) {
        String symbol = operator == null ? "*" : operator.trim();
        return switch (symbol) {
            case "+", "ADD" -> value.add(operand);
            case "-", "SUBTRACT" -> value.subtract(operand);
            case "*", "MULTIPLY" -> value.multiply(operand);
            case "/", "DIVIDE" -> {
                if (operand.signum() == 0) {
                    throw ExcelException.invalidData("A CALCULATE step cannot divide by zero.");
                }
                // A scale is required or a non-terminating quotient throws.
                yield value.divide(operand, 10, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default -> throw ExcelException.invalidData("Unknown arithmetic operator '" + symbol + "'.");
        };
    }

    /** Re-reads a column as a declared type, so a text column of digits becomes numeric. */
    private static void convert(SheetTable table, String column, String to, String format) {
        if (to == null || to.isBlank()) {
            throw ExcelException.invalidData("A CONVERT step needs a 'to' type.");
        }
        CellType target;
        try {
            target = CellType.valueOf(to.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ExcelException.invalidData("Unknown target type '" + to + "'.");
        }
        mapColumn(table, column, cell -> convertCell(cell, target, format));
    }

    private static CellValue convertCell(CellValue cell, CellType target, String format) {
        if (cell.isBlank()) {
            return CellValue.BLANK;
        }
        String text = cell.asText().trim();
        try {
            return switch (target) {
                case STRING -> CellValue.of(text);
                case INTEGER, LONG -> CellValue.of(new BigDecimal(text).longValueExact());
                case DECIMAL -> CellValue.of(new BigDecimal(text));
                case BOOLEAN -> CellValue.of("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                        || "1".equals(text) || "y".equalsIgnoreCase(text));
                case DATE -> CellValue.of(format == null || format.isBlank()
                        ? LocalDate.parse(text)
                        : LocalDate.parse(text, DateTimeFormatter.ofPattern(format)));
                case DATETIME -> CellValue.of(format == null || format.isBlank()
                        ? LocalDateTime.parse(text)
                        : LocalDateTime.parse(text, DateTimeFormatter.ofPattern(format)));
                default -> cell;
            };
        } catch (ArithmeticException | IllegalArgumentException ex) {
            // IllegalArgumentException covers NumberFormatException and DateTimeParseException, both of which
            // extend it; listing them separately is a compile error, not extra safety.
            // A value that will not convert is reported in place rather than failing the node, so one bad row
            // in ten thousand is visible in the output instead of costing the whole transform.
            return CellValue.error("#CONVERT:" + text);
        }
    }

    private static void formatDate(SheetTable table, String column, String format) {
        if (format == null || format.isBlank()) {
            throw ExcelException.invalidData("A FORMAT_DATE step needs a 'format'.");
        }
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(format);
        } catch (IllegalArgumentException ex) {
            throw ExcelException.invalidData("The date format '" + format + "' is not valid.");
        }
        mapColumn(table, column, cell -> {
            if (cell.value() instanceof LocalDateTime dateTime) {
                return CellValue.of(dateTime.format(formatter));
            }
            if (cell.value() instanceof LocalDate date) {
                return CellValue.of(date.format(formatter));
            }
            return cell;
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void mapColumn(SheetTable table, String column,
                                  java.util.function.UnaryOperator<CellValue> mapper) {
        int index = table.columnIndex(column);
        if (index < 0) {
            throw ExcelException.columnNotFound(column, table.headers());
        }
        for (List<CellValue> row : table.rows()) {
            CellValue current = table.cell(row, index);
            if (current.isBlank()) {
                continue;
            }
            table.padTo(row, index + 1);
            row.set(index, mapper.apply(current));
        }
    }

    /** Deep enough that the input table's rows are never mutated by a transform. */
    private static SheetTable copy(SheetTable table) {
        List<List<CellValue>> rows = new ArrayList<>(table.rowCount());
        for (List<CellValue> row : table.rows()) {
            rows.add(new ArrayList<>(row));
        }
        return new SheetTable(table.sheetName(), table.headers(), rows);
    }

    private static String required(Map<String, Object> step, String key) {
        String value = text(step.get(key));
        if (value == null || value.isBlank()) {
            throw ExcelException.invalidData("This transform step needs a '" + key + "'.");
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
