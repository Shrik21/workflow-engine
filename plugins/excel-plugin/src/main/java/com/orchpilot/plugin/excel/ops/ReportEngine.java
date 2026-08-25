package com.orchpilot.plugin.excel.ops;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups rows and aggregates them — the pivot behind {@code Department → Salary SUM}.
 *
 * <h2>Grouping is by value, not by row order</h2>
 *
 * Rows are bucketed by the joined text of their group columns, preserving first-appearance order. That means
 * the source does not have to be sorted first, and the output order is deterministic — a report run twice on
 * the same data produces identical files, which matters when the result is emailed or diffed.
 *
 * <h2>Non-numeric values are skipped, not treated as zero</h2>
 *
 * A blank or non-numeric cell contributes nothing to a SUM and is not counted in an AVERAGE. Treating it as
 * zero would silently pull an average down and make a total look complete when it is not — the kind of error
 * that survives review because the number looks plausible.
 */
public final class ReportEngine {

    /** The aggregate functions a report may use. */
    public enum Aggregate {
        SUM,
        COUNT,
        AVERAGE,
        MIN,
        MAX;

        static Aggregate parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw ExcelException.invalidData("Every aggregation needs a 'function'.");
            }
            String normalised = raw.trim().toUpperCase(Locale.ROOT);
            for (Aggregate aggregate : values()) {
                if (aggregate.name().equals(normalised)) {
                    return aggregate;
                }
            }
            throw ExcelException.invalidData("Unknown aggregate function '" + raw + "'.");
        }
    }

    /**
     * One aggregation.
     *
     * @param column   the column to aggregate; ignored by {@link Aggregate#COUNT} when blank
     * @param function what to compute
     * @param alias    the output column name
     */
    public record Aggregation(String column, Aggregate function, String alias) {
    }

    private ReportEngine() {
    }

    /**
     * Builds the report.
     *
     * @param groupBy      columns to group on; empty produces one row for the whole table
     * @param aggregations what to compute per group
     * @param sheetName    the name for the produced sheet
     */
    public static SheetTable report(SheetTable table, List<String> groupBy,
                                    List<Aggregation> aggregations, String sheetName) {
        if (aggregations == null || aggregations.isEmpty()) {
            throw ExcelException.invalidData("A report needs at least one aggregation.");
        }
        List<String> groups = groupBy == null ? List.of() : groupBy;
        for (String column : groups) {
            if (table.columnIndex(column) < 0) {
                throw ExcelException.columnNotFound(column, table.headers());
            }
        }
        for (Aggregation aggregation : aggregations) {
            if (aggregation.function() != Aggregate.COUNT
                    && table.columnIndex(aggregation.column()) < 0) {
                throw ExcelException.columnNotFound(aggregation.column(), table.headers());
            }
        }

        // LinkedHashMap: first-appearance order, so the output is deterministic without needing a sort.
        Map<String, List<List<CellValue>>> buckets = new LinkedHashMap<>();
        Map<String, List<CellValue>> keyValues = new LinkedHashMap<>();

        for (List<CellValue> row : table.rows()) {
            List<CellValue> keyParts = new ArrayList<>(groups.size());
            StringBuilder key = new StringBuilder();
            for (String column : groups) {
                CellValue value = table.cell(row, column);
                keyParts.add(value);
                // A separator that will not appear in ordinary data, so "A" + "BC" cannot collide with
                // "AB" + "C" and merge two genuinely different groups.
                key.append(value.asText()).append('');
            }
            String bucketKey = key.toString();
            buckets.computeIfAbsent(bucketKey, ignored -> new ArrayList<>()).add(row);
            keyValues.putIfAbsent(bucketKey, keyParts);
        }

        List<String> headers = new ArrayList<>(groups);
        for (Aggregation aggregation : aggregations) {
            headers.add(alias(aggregation));
        }

        List<List<CellValue>> rows = new ArrayList<>(buckets.size());
        for (Map.Entry<String, List<List<CellValue>>> bucket : buckets.entrySet()) {
            List<CellValue> outputRow = new ArrayList<>(headers.size());
            outputRow.addAll(keyValues.get(bucket.getKey()));
            for (Aggregation aggregation : aggregations) {
                outputRow.add(compute(table, bucket.getValue(), aggregation));
            }
            rows.add(outputRow);
        }
        return new SheetTable(sheetName, headers, rows);
    }

    private static CellValue compute(SheetTable table, List<List<CellValue>> rows, Aggregation aggregation) {
        if (aggregation.function() == Aggregate.COUNT) {
            // COUNT with a column counts populated cells; without one it counts rows. Both are things people
            // legitimately mean by "count", and the configuration distinguishes them.
            if (aggregation.column() == null || aggregation.column().isBlank()) {
                return CellValue.of(rows.size());
            }
            long populated = 0;
            for (List<CellValue> row : rows) {
                if (!table.cell(row, aggregation.column()).isBlank()) {
                    populated++;
                }
            }
            return CellValue.of(populated);
        }

        List<BigDecimal> numbers = new ArrayList<>(rows.size());
        for (List<CellValue> row : rows) {
            BigDecimal number = table.cell(row, aggregation.column()).asNumber();
            if (number != null) {
                numbers.add(number);
            }
        }
        if (numbers.isEmpty()) {
            // No numbers at all is genuinely blank, not zero — see the class note.
            return CellValue.BLANK;
        }

        return switch (aggregation.function()) {
            case SUM -> numeric(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
            case AVERAGE -> numeric(numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(numbers.size()), 6, RoundingMode.HALF_UP)
                    .stripTrailingZeros());
            case MIN -> numeric(numbers.stream().min(BigDecimal::compareTo).orElseThrow());
            case MAX -> numeric(numbers.stream().max(BigDecimal::compareTo).orElseThrow());
            case COUNT -> CellValue.of(numbers.size());
        };
    }

    /**
     * Reports a whole result as an integer so a summed total does not render as {@code 500000.0}.
     *
     * <p>Delegates rather than repeating the narrowing: this and the transform engine's CALCULATE both produce
     * computed numbers, and they drifted apart once already.
     */
    private static CellValue numeric(BigDecimal value) {
        return CellValue.computed(value);
    }

    /** Parses the node's aggregation configuration. */
    public static List<Aggregation> parseAggregations(List<Map<String, Object>> raw) {
        List<Aggregation> aggregations = new ArrayList<>();
        if (raw == null) {
            return aggregations;
        }
        for (Map<String, Object> entry : raw) {
            String column = entry.get("column") == null ? null : String.valueOf(entry.get("column"));
            Aggregate function = Aggregate.parse(entry.get("function") == null ? null
                    : String.valueOf(entry.get("function")));
            String alias = entry.get("alias") == null ? null : String.valueOf(entry.get("alias"));
            aggregations.add(new Aggregation(column, function, alias));
        }
        return aggregations;
    }

    private static String alias(Aggregation aggregation) {
        if (aggregation.alias() != null && !aggregation.alias().isBlank()) {
            return aggregation.alias();
        }
        String column = aggregation.column();
        return column == null || column.isBlank()
                ? aggregation.function().name()
                : aggregation.function().name() + "(" + column + ")";
    }
}
