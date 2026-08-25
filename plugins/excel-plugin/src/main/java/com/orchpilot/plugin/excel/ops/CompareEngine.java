package com.orchpilot.plugin.excel.ops;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two sheets and reports what changed.
 *
 * <h2>Matching by key, not by position</h2>
 *
 * Comparing row 1 to row 1 is only meaningful when both files are sorted identically and nothing was inserted —
 * which is almost never true of two exports taken a month apart. One inserted row at the top would report every
 * subsequent row as changed, burying the real difference in noise. So rows are matched on a key column, and
 * position is ignored entirely.
 *
 * <p>The result is deliberately four disjoint sets: a row is added, removed, changed or unchanged, never two
 * of those. That makes {@code added + removed + changed + unchanged} equal the total considered, so the counts
 * can be trusted in a summary without re-deriving them.
 */
public final class CompareEngine {

    /**
     * The outcome.
     *
     * @param added      keys present only in the right-hand sheet
     * @param removed    keys present only in the left-hand sheet
     * @param changed    keys in both whose compared columns differ, with the differences
     * @param unchanged  how many keys matched with no difference
     * @param duplicates keys appearing more than once, which make a comparison ambiguous
     */
    public record Result(List<Map<String, Object>> added, List<Map<String, Object>> removed,
                         List<Map<String, Object>> changed, int unchanged, List<String> duplicates) {
    }

    private CompareEngine() {
    }

    /**
     * Compares two tables on a key column.
     *
     * @param left    the baseline
     * @param right   the candidate
     * @param keyColumn the column identifying a row in both
     * @param columns which columns to compare; empty compares every column the two share
     * @param maxReported cap on entries in each list, so a wholly different pair of files does not produce an
     *                    output too large to store in a workflow variable
     */
    public static Result compare(SheetTable left, SheetTable right, String keyColumn,
                                 List<String> columns, int maxReported) {
        if (left.columnIndex(keyColumn) < 0) {
            throw ExcelException.columnNotFound(keyColumn, left.headers());
        }
        if (right.columnIndex(keyColumn) < 0) {
            throw ExcelException.columnNotFound(keyColumn, right.headers());
        }

        List<String> compared = columns == null || columns.isEmpty()
                ? sharedColumns(left, right, keyColumn)
                : columns;

        List<String> duplicates = new ArrayList<>();
        Map<String, List<CellValue>> leftByKey = index(left, keyColumn, duplicates);
        Map<String, List<CellValue>> rightByKey = index(right, keyColumn, duplicates);

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<String, List<CellValue>> entry : leftByKey.entrySet()) {
            List<CellValue> counterpart = rightByKey.get(entry.getKey());
            if (counterpart == null) {
                if (removed.size() < maxReported) {
                    removed.add(describe(entry.getKey(), left.rowAsMap(entry.getValue())));
                }
                continue;
            }
            List<Map<String, Object>> differences =
                    differences(left, entry.getValue(), right, counterpart, compared);
            if (differences.isEmpty()) {
                unchanged++;
            } else if (changed.size() < maxReported) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("key", entry.getKey());
                record.put("differences", differences);
                changed.add(record);
            }
        }

        for (Map.Entry<String, List<CellValue>> entry : rightByKey.entrySet()) {
            if (!leftByKey.containsKey(entry.getKey()) && added.size() < maxReported) {
                added.add(describe(entry.getKey(), right.rowAsMap(entry.getValue())));
            }
        }
        return new Result(added, removed, changed, unchanged, duplicates);
    }

    /** @return one entry per column whose value differs, naming both sides */
    private static List<Map<String, Object>> differences(SheetTable left, List<CellValue> leftRow,
                                                         SheetTable right, List<CellValue> rightRow,
                                                         List<String> columns) {
        List<Map<String, Object>> differences = new ArrayList<>();
        for (String column : columns) {
            CellValue before = left.cell(leftRow, column);
            CellValue after = right.cell(rightRow, column);
            // Compared as text so a value stored as a number in one file and as text in the other does not
            // register as a change; that difference is a formatting artefact, not a data change.
            if (!before.asText().equals(after.asText())) {
                Map<String, Object> difference = new LinkedHashMap<>();
                difference.put("column", column);
                difference.put("before", before.toJson());
                difference.put("after", after.toJson());
                differences.add(difference);
            }
        }
        return differences;
    }

    /**
     * Indexes rows by key.
     *
     * <p>A repeated key is recorded and the first occurrence wins. Silently overwriting would make the
     * comparison depend on row order; failing outright would refuse a job over a duplicate in a column the
     * user may not have realised was not unique. Reporting it lets them decide.
     */
    private static Map<String, List<CellValue>> index(SheetTable table, String keyColumn,
                                                      List<String> duplicates) {
        Map<String, List<CellValue>> byKey = new LinkedHashMap<>();
        for (List<CellValue> row : table.rows()) {
            String key = table.cell(row, keyColumn).asText();
            if (byKey.putIfAbsent(key, row) != null && !duplicates.contains(key)) {
                duplicates.add(key);
            }
        }
        return byKey;
    }

    private static List<String> sharedColumns(SheetTable left, SheetTable right, String keyColumn) {
        Set<String> shared = new LinkedHashSet<>();
        for (String header : left.headers()) {
            if (!header.equalsIgnoreCase(keyColumn) && right.columnIndex(header) >= 0) {
                shared.add(header);
            }
        }
        return new ArrayList<>(shared);
    }

    private static Map<String, Object> describe(String key, Map<String, Object> row) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("key", key);
        described.put("row", row);
        return described;
    }
}
