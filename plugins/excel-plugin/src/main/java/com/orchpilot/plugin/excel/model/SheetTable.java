package com.orchpilot.plugin.excel.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sheet as headers plus rows — the single in-memory shape every operation works on.
 *
 * <h2>Why one shape, decoupled from POI</h2>
 *
 * Filtering, sorting, transforming, comparing and aggregating are all operations on a table, and none of them
 * needs to know what a {@code XSSFCell} is. Reading POI into this once, at the edge, buys three things: those
 * operations become pure functions that are trivial to test without fabricating a workbook; they compose (a
 * filter then a sort then a report is three calls on the same type); and the day a CSV or a JSON array is the
 * input instead, nothing downstream changes.
 *
 * <p>Mutating methods return {@code this} or a new table rather than editing in place where the distinction
 * matters, and are documented individually. The class is not thread-safe; one node attempt owns one table.
 */
public final class SheetTable {

    private final String sheetName;
    private final List<String> headers;
    private final List<List<CellValue>> rows;

    public SheetTable(String sheetName, List<String> headers, List<List<CellValue>> rows) {
        this.sheetName = sheetName == null ? "Sheet1" : sheetName;
        this.headers = new ArrayList<>(headers == null ? List.of() : headers);
        this.rows = new ArrayList<>(rows == null ? List.of() : rows);
    }

    public static SheetTable empty(String sheetName) {
        return new SheetTable(sheetName, new ArrayList<>(), new ArrayList<>());
    }

    public String sheetName() {
        return sheetName;
    }

    /** @return the header names, in column order; mutating the returned list does not affect the table */
    public List<String> headers() {
        return new ArrayList<>(headers);
    }

    public int rowCount() {
        return rows.size();
    }

    public int columnCount() {
        return headers.size();
    }

    /** @return the underlying rows; intended for iteration, and mutated in place by the row operations */
    public List<List<CellValue>> rows() {
        return rows;
    }

    /**
     * @param header the column name
     * @return its zero-based index, or -1 when the table has no such column
     */
    public int columnIndex(String header) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (header.equalsIgnoreCase(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param row    the row
     * @param column the column name
     * @return the cell, or {@link CellValue#BLANK} when the column does not exist or the row is short
     */
    public CellValue cell(List<CellValue> row, String column) {
        return cell(row, columnIndex(column));
    }

    public CellValue cell(List<CellValue> row, int columnIndex) {
        // Short rows are normal: Excel does not pad a row out to the sheet's width.
        if (columnIndex < 0 || row == null || columnIndex >= row.size()) {
            return CellValue.BLANK;
        }
        CellValue value = row.get(columnIndex);
        return value == null ? CellValue.BLANK : value;
    }

    /** Widens a row in place so an index is addressable, padding with blanks. */
    public void padTo(List<CellValue> row, int size) {
        while (row.size() < size) {
            row.add(CellValue.BLANK);
        }
    }

    // ------------------------------------------------------------------ column operations

    /** Appends a column. Existing rows are not padded; {@link #cell} treats the gap as blank. */
    public void addColumn(String header) {
        headers.add(header);
    }

    /**
     * Renames a column.
     *
     * @return whether a column with that name existed
     */
    public boolean renameColumn(String from, String to) {
        int index = columnIndex(from);
        if (index < 0) {
            return false;
        }
        headers.set(index, to);
        return true;
    }

    /**
     * Removes a column and the corresponding cell from every row.
     *
     * @return whether a column with that name existed
     */
    public boolean removeColumn(String header) {
        int index = columnIndex(header);
        if (index < 0) {
            return false;
        }
        headers.remove(index);
        for (List<CellValue> row : rows) {
            if (index < row.size()) {
                row.remove(index);
            }
        }
        return true;
    }

    /** Moves a column, and the matching cell in every row, to a new position. */
    public boolean moveColumn(String header, int toIndex) {
        int from = columnIndex(header);
        if (from < 0 || toIndex < 0 || toIndex >= headers.size()) {
            return false;
        }
        headers.add(toIndex, headers.remove(from));
        for (List<CellValue> row : rows) {
            padTo(row, headers.size());
            row.add(toIndex, row.remove(from));
        }
        return true;
    }

    // ------------------------------------------------------------------ conversion

    /**
     * One row as a map of header to JSON-ready value.
     *
     * <p>A {@link LinkedHashMap} so the output preserves column order — a JSON object whose keys shuffle
     * between runs is unpleasant to read in an execution log and breaks naive downstream diffing.
     */
    public Map<String, Object> rowAsMap(List<CellValue> row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            map.put(headers.get(i), cell(row, i).toJson());
        }
        return map;
    }

    /** @return every row as a map, the {@code JSON} return shape */
    public List<Map<String, Object>> toMaps() {
        List<Map<String, Object>> maps = new ArrayList<>(rows.size());
        for (List<CellValue> row : rows) {
            maps.add(rowAsMap(row));
        }
        return maps;
    }

    /** @return rows as positional lists of values, the {@code ROWS} return shape */
    public List<List<Object>> toRows() {
        List<List<Object>> result = new ArrayList<>(rows.size());
        for (List<CellValue> row : rows) {
            List<Object> values = new ArrayList<>(headers.size());
            for (int i = 0; i < headers.size(); i++) {
                values.add(cell(row, i).toJson());
            }
            result.add(values);
        }
        return result;
    }

    /** @return one entry per column, each holding that column's values, the {@code COLUMNS} return shape */
    public Map<String, List<Object>> toColumns() {
        Map<String, List<Object>> columns = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            List<Object> values = new ArrayList<>(rows.size());
            for (List<CellValue> row : rows) {
                values.add(cell(row, i).toJson());
            }
            columns.put(headers.get(i), values);
        }
        return columns;
    }

    /** @return a copy with the same headers and the given rows, used by every non-mutating operation */
    public SheetTable withRows(List<List<CellValue>> newRows) {
        return new SheetTable(sheetName, headers, newRows);
    }

    /** @return a copy under a different sheet name, used by split */
    public SheetTable renamedTo(String newSheetName) {
        return new SheetTable(newSheetName, headers, rows);
    }
}
