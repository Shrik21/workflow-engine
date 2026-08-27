package com.orchpilot.plugin.excel.io;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.ExcelLimits;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a table from JSON the workflow supplies.
 *
 * <h2>Columns are the union of every object's keys</h2>
 *
 * Taking the first object's keys as the schema is the obvious approach and quietly loses data: an array whose
 * later entries carry an extra field would silently drop it. Scanning every object costs one pass and means
 * nothing is lost — an object missing a key simply gets a blank in that column.
 *
 * <p>Key order follows first appearance, so the generated spreadsheet's columns are in the order a reader of
 * the JSON would expect rather than alphabetical or arbitrary.
 *
 * <h2>Nested values are flattened to text</h2>
 *
 * A cell holds one value; a nested object or array has no faithful representation in one. Rather than silently
 * dropping it or inventing a column explosion, a nested value is rendered as its JSON-ish text so the
 * information survives and is visibly not tabular. Callers wanting real columns flatten upstream, where they
 * know what the nesting means.
 */
public final class JsonTables {

    private JsonTables() {
    }

    /**
     * Converts a JSON array of objects into a table.
     *
     * @param rows      the array, as the engine deserialised it
     * @param sheetName the name for the resulting sheet
     * @param limits    the bounds this attempt must respect
     * @throws ExcelException when the input is not an array of objects
     */
    public static SheetTable fromMaps(List<?> rows, String sheetName, ExcelLimits limits) {
        if (rows == null || rows.isEmpty()) {
            return SheetTable.empty(sheetName);
        }
        limits.checkRowCount(rows.size());

        Set<String> columns = new LinkedHashSet<>();
        for (Object entry : rows) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw ExcelException.invalidData(
                        "Expected an array of JSON objects; found a " + describe(entry) + ".");
            }
            for (Object key : map.keySet()) {
                columns.add(String.valueOf(key));
            }
        }
        limits.checkColumnCount(columns.size());

        List<String> headers = new ArrayList<>(columns);
        List<List<CellValue>> data = new ArrayList<>(rows.size());
        for (Object entry : rows) {
            Map<?, ?> map = (Map<?, ?>) entry;
            List<CellValue> row = new ArrayList<>(headers.size());
            for (String header : headers) {
                row.add(toCell(map.get(header)));
            }
            data.add(row);
        }
        return new SheetTable(sheetName, headers, data);
    }

    /**
     * Converts one JSON value into a typed cell.
     *
     * <p>Strings are examined for a date shape, because JSON has no date type and an ISO string is what every
     * producer emits. Getting that wrong would put text where a workflow expects to sort chronologically.
     */
    public static CellValue toCell(Object value) {
        if (value == null) {
            return CellValue.BLANK;
        }
        if (value instanceof Boolean flag) {
            return CellValue.of(flag);
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return CellValue.of(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return CellValue.of(BigDecimal.valueOf(number.doubleValue()));
        }
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            return CellValue.of(render(value));
        }
        String text = String.valueOf(value);
        CellValue temporal = asTemporal(text);
        return temporal != null ? temporal : CellValue.of(text);
    }

    /** @return a date or date-time cell when the text is ISO-shaped, or null when it is ordinary text */
    private static CellValue asTemporal(String text) {
        // Cheap shape check first: parsing every string in a large sheet through two exception-throwing
        // parsers is far more expensive than the work itself.
        if (text.length() < 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
            return null;
        }
        try {
            return CellValue.of(LocalDate.parse(text));
        } catch (DateTimeParseException ex) {
            try {
                return CellValue.of(LocalDateTime.parse(text));
            } catch (DateTimeParseException nested) {
                return null;
            }
        }
    }

    /** A compact rendering for a nested value, good enough to read in a cell. */
    private static String render(Object value) {
        StringBuilder out = new StringBuilder();
        renderInto(value, out);
        return out.toString();
    }

    private static void renderInto(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(entry.getKey()).append(": ");
                renderInto(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                renderInto(element, out);
            }
            out.append(']');
        } else {
            out.append(value);
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
