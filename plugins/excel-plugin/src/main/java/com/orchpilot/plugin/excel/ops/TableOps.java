package com.orchpilot.plugin.excel.ops;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sorting, merging and splitting — the operations that rearrange whole tables.
 *
 * <p>All pure: each returns new tables and leaves its input alone, which is what lets a workflow chain
 * filter → sort → split without a step quietly mutating what an earlier one produced.
 */
public final class TableOps {

    /** One level of a sort. */
    public record SortKey(String column, boolean ascending) {
    }

    private TableOps() {
    }

    // ------------------------------------------------------------------ sort

    /**
     * Sorts by one or more columns.
     *
     * <p>Stable, so rows equal on every key keep their original order — which makes a two-step sort behave the
     * way a person expects and makes the output reproducible.
     *
     * @throws ExcelException when a key names a column the sheet does not have
     */
    public static SheetTable sort(SheetTable table, List<SortKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return table;
        }
        int[] indexes = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            int index = table.columnIndex(keys.get(i).column());
            if (index < 0) {
                throw ExcelException.columnNotFound(keys.get(i).column(), table.headers());
            }
            indexes[i] = index;
        }

        Comparator<List<CellValue>> comparator = null;
        for (int i = 0; i < keys.size(); i++) {
            int index = indexes[i];
            Comparator<List<CellValue>> level =
                    Comparator.comparing(row -> table.cell(row, index));
            if (!keys.get(i).ascending()) {
                level = level.reversed();
            }
            comparator = comparator == null ? level : comparator.thenComparing(level);
        }

        List<List<CellValue>> sorted = new ArrayList<>(table.rows());
        sorted.sort(comparator);
        return table.withRows(sorted);
    }

    /** Parses the node's sort configuration, e.g. {@code [{"column":"Salary","order":"DESC"}]}. */
    public static List<SortKey> parseSortKeys(List<Map<String, Object>> raw) {
        List<SortKey> keys = new ArrayList<>();
        if (raw == null) {
            return keys;
        }
        for (Map<String, Object> entry : raw) {
            Object column = entry.get("column");
            if (column == null || String.valueOf(column).isBlank()) {
                throw ExcelException.invalidData("Every sort key needs a 'column'.");
            }
            String order = entry.get("order") == null ? "ASC" : String.valueOf(entry.get("order"));
            keys.add(new SortKey(String.valueOf(column), !"DESC".equalsIgnoreCase(order.trim())));
        }
        return keys;
    }

    // ------------------------------------------------------------------ merge

    /**
     * Combines tables into one, unioning their columns.
     *
     * <p>Columns are unioned rather than requiring a match, because the case that actually arrives is three
     * monthly exports where February gained a column. Failing that merge would be correct and useless; instead
     * every column that appears anywhere becomes a column of the result, and a row from a file that lacked one
     * gets a blank there.
     *
     * <p>Rows are re-indexed by header name, not by position — two files with the same columns in a different
     * order merge correctly, which positional concatenation would silently corrupt.
     *
     * @param sheetName the name for the combined sheet
     */
    public static SheetTable merge(List<SheetTable> tables, String sheetName) {
        if (tables == null || tables.isEmpty()) {
            return SheetTable.empty(sheetName);
        }
        Set<String> unionedHeaders = new LinkedHashSet<>();
        for (SheetTable table : tables) {
            unionedHeaders.addAll(table.headers());
        }
        List<String> headers = new ArrayList<>(unionedHeaders);

        List<List<CellValue>> merged = new ArrayList<>();
        for (SheetTable table : tables) {
            for (List<CellValue> row : table.rows()) {
                List<CellValue> aligned = new ArrayList<>(headers.size());
                for (String header : headers) {
                    // By name: the source may order its columns differently, or not have this one at all.
                    aligned.add(table.cell(row, header));
                }
                merged.add(aligned);
            }
        }
        return new SheetTable(sheetName, headers, merged);
    }

    // ------------------------------------------------------------------ split

    /**
     * Splits a table into one table per distinct value of a column.
     *
     * <p>Insertion-ordered, so the output order follows the order values first appear in the source rather
     * than an arbitrary hash order — a split run twice on the same input produces the same files in the same
     * order.
     *
     * @param column the column whose values decide the grouping
     * @return one table per distinct value, keyed by that value
     * @throws ExcelException when the column does not exist
     */
    public static Map<String, SheetTable> split(SheetTable table, String column) {
        int index = table.columnIndex(column);
        if (index < 0) {
            throw ExcelException.columnNotFound(column, table.headers());
        }
        Map<String, List<List<CellValue>>> grouped = new LinkedHashMap<>();
        for (List<CellValue> row : table.rows()) {
            CellValue value = table.cell(row, index);
            // A blank grouping value is data too; bucketing it explicitly beats dropping those rows silently.
            String key = value.isBlank() ? "(blank)" : value.asText();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        Map<String, SheetTable> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<List<CellValue>>> entry : grouped.entrySet()) {
            result.put(entry.getKey(),
                    new SheetTable(entry.getKey(), table.headers(), entry.getValue()));
        }
        return result;
    }
}
