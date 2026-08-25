package com.orchpilot.plugin.excel;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.io.CsvCodec;
import com.orchpilot.plugin.excel.io.JsonTables;
import com.orchpilot.plugin.excel.io.WorkbookReader;
import com.orchpilot.plugin.excel.io.WorkbookWriter;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.ExcelLimits;
import com.orchpilot.plugin.excel.model.ExcelOperation;
import com.orchpilot.plugin.excel.model.SheetTable;
import com.orchpilot.plugin.excel.ops.CompareEngine;
import com.orchpilot.plugin.excel.ops.Conditions;
import com.orchpilot.plugin.excel.ops.ReportEngine;
import com.orchpilot.plugin.excel.ops.TableOps;
import com.orchpilot.plugin.excel.ops.TransformEngine;
import com.orchpilot.plugin.excel.ops.ValidationEngine;
import com.orchpilot.workflow.sdk.context.WorkflowFileAccess;
import com.orchpilot.workflow.sdk.context.WorkflowFileHandle;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes one Excel operation.
 *
 * <h2>Constructed per attempt</h2>
 *
 * Holds the configuration, the limits and the execution's file accessor, so the individual handlers read as
 * the operation and nothing else. Nothing here is shared between executions, which is what makes concurrent
 * Excel nodes safe without any locking.
 *
 * <h2>Reading and writing are bracketed in one place</h2>
 *
 * {@link #table()} opens a workbook, reads a sheet and closes the workbook; {@link #store} writes bytes through
 * the engine's file accessor. Every handler goes through those two, so no handler has to remember to close a
 * workbook or think about where a file lives.
 */
final class ExcelOperations {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String CSV_CONTENT_TYPE = "text/csv";

    private final ExcelOperation operation;
    private final NodeConfiguration cfg;
    private final WorkflowFileAccess files;
    private final ExcelLimits limits;

    ExcelOperations(ExcelOperation operation, NodeConfiguration cfg, WorkflowFileAccess files) {
        this.operation = operation;
        this.cfg = cfg;
        this.files = files;
        this.limits = ExcelLimits.from(cfg);
    }

    // ------------------------------------------------------------------ dispatch

    NodeExecutionResult run() {
        return switch (operation) {
            case READ, EXCEL_TO_JSON -> read();
            case READ_WORKBOOK -> readWorkbook();
            case SHEET_METADATA -> sheetMetadata();
            case GET_CELL -> getCell();
            case SET_CELL -> setCell();
            case SEARCH -> search();
            case FILTER -> filter();
            case SORT -> sort();
            case VALIDATE -> validate();
            case TRANSFORM -> transform();
            case COMPARE -> compare();
            case GENERATE_REPORT -> report();
            case CREATE -> create();
            case WRITE -> write();
            case APPEND -> append();
            case UPDATE -> update();
            case DELETE_ROW -> deleteRow();
            case CREATE_SHEET, RENAME_SHEET, COPY_SHEET, DELETE_SHEET -> sheetOperation();
            case MERGE -> merge();
            case SPLIT -> split();
            case JSON_TO_EXCEL -> jsonToExcel();
            case CSV_TO_EXCEL -> csvToExcel();
            case EXCEL_TO_CSV -> excelToCsv();
        };
    }

    // ------------------------------------------------------------------ reading

    private NodeExecutionResult read() {
        SheetTable table = table();
        Map<String, Object> outputs = base(table);
        String returnType = cfg.getString("returnType", "JSON").toUpperCase(java.util.Locale.ROOT);
        outputs.put("returnType", returnType);
        outputs.put("data", switch (returnType) {
            case "ROWS" -> table.toRows();
            case "COLUMNS" -> table.toColumns();
            case "TABLE" -> Map.of("headers", table.headers(), "rows", table.toRows());
            default -> table.toMaps();
        });
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult readWorkbook() {
        WorkflowFileHandle handle = requireHandle();
        try (WorkbookReader reader = open(handle)) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("success", true);
            outputs.put("operation", operation.name());
            outputs.put("fileId", handle.fileId());
            outputs.put("fileName", handle.fileName());
            List<Map<String, Object>> sheets = reader.describeSheets();
            outputs.put("sheets", sheets);
            outputs.put("sheetCount", sheets.size());
            return NodeExecutionResult.success(outputs);
        }
    }

    private NodeExecutionResult sheetMetadata() {
        SheetTable table = table();
        Map<String, Object> outputs = base(table);
        outputs.put("headers", table.headers());
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult getCell() {
        CellReference reference = CellReference.parse(cfg.requireString("cell"));
        SheetTable table = tableWithoutHeader();

        // Row 1 of the sheet is index 0 of the raw table, so a reference is used exactly as written.
        List<CellValue> row = reference.row() < table.rowCount()
                ? table.rows().get(reference.row()) : List.of();
        CellValue value = table.cell(row, reference.column());

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation.name());
        outputs.put("cell", reference.original());
        outputs.put("value", value.toJson());
        outputs.put("type", value.type().name());
        if (value.formula() != null) {
            outputs.put("formula", "=" + value.formula());
        }
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ shaping

    private NodeExecutionResult search() {
        SheetTable table = table();
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("column", cfg.requireString("column"));
        condition.put("operator", cfg.getString("operator", "EQUALS"));
        condition.put("value", cfg.getString("value", null));
        condition.put("caseSensitive", cfg.getBoolean("caseSensitive", false));

        // Search is a filter with one condition; see Conditions for why there is only one evaluator.
        List<Conditions.Condition> conditions = Conditions.parse(List.of(condition),
                cfg.getBoolean("allowRegex", false));
        SheetTable matched = Conditions.apply(table, conditions, Conditions.Combine.AND);
        return matchedResult(table, matched);
    }

    private NodeExecutionResult filter() {
        SheetTable table = table();
        List<Conditions.Condition> conditions = Conditions.parse(mapList("conditions"),
                cfg.getBoolean("allowRegex", false));
        if (conditions.isEmpty()) {
            throw ExcelException.invalidData("A filter needs at least one condition.");
        }
        SheetTable matched = Conditions.apply(table,
                conditions, Conditions.Combine.valueOf(cfg.getString("combine", "AND")
                        .toUpperCase(java.util.Locale.ROOT)));
        return matchedResult(table, matched);
    }

    private NodeExecutionResult sort() {
        SheetTable table = table();
        SheetTable sorted = TableOps.sort(table, TableOps.parseSortKeys(mapList("sortBy")));
        Map<String, Object> outputs = base(sorted);
        outputs.put("data", sorted.toMaps());
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult validate() {
        SheetTable table = table();
        List<ValidationEngine.Failure> failures = ValidationEngine.validate(table, mapList("rules"),
                (int) cfg.getLong("maxErrors", 500));

        List<Map<String, Object>> errors = new ArrayList<>(failures.size());
        for (ValidationEngine.Failure failure : failures) {
            errors.add(failure.toMap());
        }

        boolean valid = failures.isEmpty();
        Map<String, Object> outputs = base(table);
        outputs.put("valid", valid);
        outputs.put("errorCount", failures.size());
        outputs.put("errors", errors);
        outputs.put("success", valid || !cfg.getBoolean("failOnInvalid", false));

        if (!valid && cfg.getBoolean("failOnInvalid", false)) {
            throw ExcelException.validationFailed(failures.size());
        }
        // Reporting invalid data is the node doing its job, so it succeeds and the workflow branches on
        // 'valid'. See ValidationEngine for why that is not conflated with the check failing to run.
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult transform() {
        SheetTable table = table();
        SheetTable transformed = TransformEngine.apply(table, mapList("steps"));
        WorkflowFileHandle stored = store(WorkbookWriter.singleSheet(transformed),
                outputName("transformed.xlsx"), XLSX_CONTENT_TYPE);

        Map<String, Object> outputs = base(transformed);
        outputs.put("data", transformed.toMaps());
        addFile(outputs, stored);
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult compare() {
        SheetTable left = table();
        SheetTable right = tableOf(cfg.requireString("compareFileId"),
                cfg.getString("compareSheet", cfg.getString("sheetName", null)));

        CompareEngine.Result result = CompareEngine.compare(left, right, cfg.requireString("keyColumn"),
                stringList("columns"), (int) cfg.getLong("maxReported", 500));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation.name());
        outputs.put("added", result.added().size());
        outputs.put("removed", result.removed().size());
        outputs.put("changed", result.changed().size());
        outputs.put("unchanged", result.unchanged());
        outputs.put("addedRows", result.added());
        outputs.put("removedRows", result.removed());
        outputs.put("changedRows", result.changed());
        if (!result.duplicates().isEmpty()) {
            outputs.put("duplicateKeys", result.duplicates());
        }
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult report() {
        SheetTable table = table();
        SheetTable summary = ReportEngine.report(table, stringList("groupBy"),
                ReportEngine.parseAggregations(mapList("aggregations")), "Report");

        WorkflowFileHandle stored = store(WorkbookWriter.singleSheet(summary),
                outputName("report.xlsx"), XLSX_CONTENT_TYPE);

        Map<String, Object> outputs = base(summary);
        outputs.put("data", summary.toMaps());
        addFile(outputs, stored);
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ writing

    private NodeExecutionResult create() {
        List<String> columns = stringList("columns");
        if (columns.isEmpty()) {
            throw ExcelException.invalidData("Creating a workbook needs at least one column.");
        }
        limits.checkColumnCount(columns.size());

        List<List<CellValue>> rows = new ArrayList<>();
        for (Object entry : rawList("data")) {
            if (!(entry instanceof List<?> values)) {
                throw ExcelException.invalidData(
                        "'data' must be an array of arrays, one per row, in column order.");
            }
            List<CellValue> row = new ArrayList<>(values.size());
            for (Object value : values) {
                row.add(JsonTables.toCell(value));
            }
            rows.add(row);
        }
        limits.checkRowCount(rows.size());

        SheetTable table = new SheetTable(cfg.getString("sheetName", "Sheet1"), columns, rows);
        return storedResult(table, cfg.getString("fileName", "workbook.xlsx"));
    }

    private NodeExecutionResult write() {
        SheetTable table = JsonTables.fromMaps(rawList("data"),
                cfg.getString("sheetName", "Sheet1"), limits);
        return storedResult(table, outputName("workbook.xlsx"));
    }

    private NodeExecutionResult append() {
        SheetTable table = table();
        List<?> incoming = rawList("rows");
        if (incoming.isEmpty()) {
            throw ExcelException.invalidData("Appending needs at least one row.");
        }
        for (Object entry : incoming) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw ExcelException.invalidData("Each appended row must be a JSON object keyed by column.");
            }
            List<CellValue> row = new ArrayList<>(table.columnCount());
            for (String header : table.headers()) {
                // Keyed by name so the caller does not have to know the sheet's column order.
                row.add(JsonTables.toCell(map.get(header)));
            }
            table.rows().add(row);
        }
        limits.checkRowCount(table.rowCount());
        return storedResult(table, outputName("appended.xlsx"), incoming.size(), "appendedRows");
    }

    private NodeExecutionResult update() {
        SheetTable table = table();
        String findColumn = cfg.requireString("findColumn");
        if (table.columnIndex(findColumn) < 0) {
            throw ExcelException.columnNotFound(findColumn, table.headers());
        }
        String findValue = cfg.requireString("findValue");
        Map<String, Object> updates = cfg.getMap("updates");
        if (updates.isEmpty()) {
            throw ExcelException.invalidData("An update needs at least one column to change.");
        }
        for (String column : updates.keySet()) {
            if (table.columnIndex(column) < 0) {
                throw ExcelException.columnNotFound(column, table.headers());
            }
        }

        boolean updateAll = cfg.getBoolean("updateAll", true);
        int updated = 0;
        for (List<CellValue> row : table.rows()) {
            if (!table.cell(row, findColumn).asText().equals(findValue)) {
                continue;
            }
            for (Map.Entry<String, Object> change : updates.entrySet()) {
                int index = table.columnIndex(change.getKey());
                table.padTo(row, index + 1);
                row.set(index, JsonTables.toCell(change.getValue()));
            }
            updated++;
            if (!updateAll) {
                break;
            }
        }
        if (updated == 0) {
            throw ExcelException.rowNotFound(findColumn + " = " + findValue);
        }
        return storedResult(table, outputName("updated.xlsx"), updated, "updatedRows");
    }

    private NodeExecutionResult deleteRow() {
        SheetTable table = table();
        String findColumn = cfg.requireString("findColumn");
        if (table.columnIndex(findColumn) < 0) {
            throw ExcelException.columnNotFound(findColumn, table.headers());
        }
        String findValue = cfg.requireString("findValue");
        boolean deleteAll = cfg.getBoolean("deleteAll", false);

        List<List<CellValue>> kept = new ArrayList<>(table.rowCount());
        int deleted = 0;
        for (List<CellValue> row : table.rows()) {
            boolean matches = table.cell(row, findColumn).asText().equals(findValue);
            if (matches && (deleteAll || deleted == 0)) {
                deleted++;
                continue;
            }
            kept.add(row);
        }
        if (deleted == 0) {
            throw ExcelException.rowNotFound(findColumn + " = " + findValue);
        }
        SheetTable remaining = table.withRows(kept);
        return storedResult(remaining, outputName("updated.xlsx"), deleted, "deletedRows");
    }

    private NodeExecutionResult setCell() {
        CellReference reference = CellReference.parse(cfg.requireString("cell"));
        SheetTable table = tableWithoutHeader();

        while (table.rowCount() <= reference.row()) {
            table.rows().add(new ArrayList<>());
        }
        while (table.columnCount() <= reference.column()) {
            table.addColumn("Column" + (table.columnCount() + 1));
        }
        List<CellValue> row = table.rows().get(reference.row());
        table.padTo(row, reference.column() + 1);
        row.set(reference.column(), JsonTables.toCell(cfg.getString("value", null)));

        return storedResult(table, outputName("updated.xlsx"), 1, "updatedCells");
    }

    // ------------------------------------------------------------------ sheets

    /**
     * Sheet-level restructuring.
     *
     * <p>Done by reading every sheet into tables and writing a new workbook, rather than by mutating a POI
     * workbook in place. That keeps one write path — the streaming writer — and means the operation cannot
     * accidentally carry over macro storage or embedded objects from the source, which a copy-the-workbook
     * approach would.
     */
    private NodeExecutionResult sheetOperation() {
        WorkflowFileHandle handle = requireHandle();
        String targetSheet = cfg.getString("sheetName", null);
        String newName = cfg.getString("newSheetName", null);

        List<SheetTable> tables = new ArrayList<>();
        List<String> names;
        try (WorkbookReader reader = open(handle)) {
            names = reader.sheetNames();
            for (String name : names) {
                tables.add(reader.read(name, cfg.getInt("headerRow", -1), 0, 0, false));
            }
        }

        switch (operation) {
            case CREATE_SHEET -> {
                requireNewName(newName);
                if (nameTaken(tables, newName)) {
                    throw ExcelException.invalidData("The workbook already has a sheet named '"
                            + newName + "'.");
                }
                tables.add(SheetTable.empty(newName));
            }
            case RENAME_SHEET -> {
                requireNewName(newName);
                int index = indexOf(tables, requireSheetName(targetSheet, names));
                tables.set(index, tables.get(index).renamedTo(newName));
            }
            case COPY_SHEET -> {
                requireNewName(newName);
                int index = indexOf(tables, requireSheetName(targetSheet, names));
                tables.add(tables.get(index).renamedTo(newName));
            }
            case DELETE_SHEET -> {
                int index = indexOf(tables, requireSheetName(targetSheet, names));
                if (tables.size() == 1) {
                    // Excel refuses to open a workbook with no sheets, so producing one would be a file
                    // nobody can use.
                    throw ExcelException.invalidData(
                            "A workbook must keep at least one sheet; this is its only one.");
                }
                tables.remove(index);
            }
            default -> throw ExcelException.invalidData("Not a sheet operation: " + operation);
        }

        byte[] bytes;
        try (WorkbookWriter writer = new WorkbookWriter()) {
            for (SheetTable table : tables) {
                writer.addSheet(table);
            }
            bytes = writer.toBytes();
        }
        WorkflowFileHandle stored = store(bytes, outputName(handle.fileName()), XLSX_CONTENT_TYPE);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation.name());
        outputs.put("sheetCount", tables.size());
        List<String> resulting = new ArrayList<>(tables.size());
        for (SheetTable table : tables) {
            resulting.add(table.sheetName());
        }
        outputs.put("sheets", resulting);
        addFile(outputs, stored);
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ whole-file

    private NodeExecutionResult merge() {
        List<String> fileIds = stringList("fileIds");
        if (fileIds.size() < 2) {
            throw ExcelException.invalidData("Merging needs at least two files.");
        }
        String sheetName = cfg.getString("sheetName", null);

        List<SheetTable> tables = new ArrayList<>(fileIds.size());
        int totalRows = 0;
        for (String fileId : fileIds) {
            SheetTable table = tableOf(fileId, sheetName);
            totalRows += table.rowCount();
            // Checked as files are read, so a merge that would exceed the limit stops early rather than
            // after loading every input.
            limits.checkRowCount(totalRows);
            tables.add(table);
        }

        SheetTable merged = TableOps.merge(tables, cfg.getString("sheetName", "Merged"));
        WorkflowFileHandle stored = store(WorkbookWriter.singleSheet(merged),
                cfg.getString("outputFileName", "merged.xlsx"), XLSX_CONTENT_TYPE);

        Map<String, Object> outputs = base(merged);
        outputs.put("mergedFiles", fileIds.size());
        addFile(outputs, stored);
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult split() {
        SheetTable table = table();
        Map<String, SheetTable> parts = TableOps.split(table, cfg.requireString("splitColumn"));

        int maxFiles = (int) cfg.getLong("maxFiles", 100);
        if (parts.size() > maxFiles) {
            throw ExcelException.invalidData("Splitting on '" + cfg.requireString("splitColumn")
                    + "' would produce " + parts.size() + " files, above the limit of " + maxFiles
                    + ". Raise 'maxFiles' or split on a column with fewer distinct values.");
        }

        String pattern = cfg.getString("fileNamePattern", "{value}.xlsx");
        List<Map<String, Object>> produced = new ArrayList<>(parts.size());
        for (Map.Entry<String, SheetTable> part : parts.entrySet()) {
            WorkflowFileHandle stored = store(WorkbookWriter.singleSheet(part.getValue()),
                    pattern.replace("{value}", part.getKey()), XLSX_CONTENT_TYPE);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", part.getKey());
            entry.put("fileId", stored.fileId());
            entry.put("fileName", stored.fileName());
            entry.put("rowCount", part.getValue().rowCount());
            produced.add(entry);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation.name());
        outputs.put("fileCount", produced.size());
        outputs.put("files", produced);
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ conversion

    private NodeExecutionResult jsonToExcel() {
        SheetTable table = JsonTables.fromMaps(rawList("data"),
                cfg.getString("sheetName", "Sheet1"), limits);
        return storedResult(table, cfg.getString("fileName", "data.xlsx"));
    }

    private NodeExecutionResult csvToExcel() {
        SheetTable table = CsvCodec.read(cfg.requireString("csv"),
                CsvCodec.delimiter(cfg.getString("delimiter", ",")),
                quoteChar(), cfg.getBoolean("includeHeader", true),
                cfg.getString("sheetName", "Sheet1"), limits);
        return storedResult(table, cfg.getString("fileName", "data.xlsx"));
    }

    private NodeExecutionResult excelToCsv() {
        SheetTable table = table();
        String csv = CsvCodec.write(table, CsvCodec.delimiter(cfg.getString("delimiter", ",")),
                quoteChar(), cfg.getBoolean("includeHeader", true));

        WorkflowFileHandle stored = store(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                cfg.getString("outputFileName", "export.csv"), CSV_CONTENT_TYPE);

        Map<String, Object> outputs = base(table);
        addFile(outputs, stored);
        if (cfg.getBoolean("returnContent", false)) {
            outputs.put("csv", csv);
        }
        return NodeExecutionResult.success(outputs);
    }

    // ------------------------------------------------------------------ file plumbing

    /** Reads the configured file and sheet into a table. */
    private SheetTable table() {
        return tableOf(cfg.requireString("fileId"), cfg.getString("sheetName", null));
    }

    private SheetTable tableOf(String fileId, String sheetName) {
        WorkflowFileHandle handle = requireHandle(fileId);
        try (WorkbookReader reader = open(handle)) {
            return reader.read(sheetName, cfg.getInt("headerRow", -1),
                    cfg.getInt("startRow", 0), cfg.getInt("endRow", 0),
                    cfg.getBoolean("readFormulas", false));
        }
    }

    /** Reads with no header, so cell references address the sheet exactly as Excel numbers it. */
    private SheetTable tableWithoutHeader() {
        WorkflowFileHandle handle = requireHandle();
        try (WorkbookReader reader = open(handle)) {
            return reader.read(cfg.getString("sheetName", null), 0, 0, 0,
                    cfg.getBoolean("readFormulas", false));
        }
    }

    private WorkflowFileHandle requireHandle() {
        return requireHandle(cfg.requireString("fileId"));
    }

    private WorkflowFileHandle requireHandle(String fileId) {
        WorkflowFileHandle handle = files.find(fileId)
                .orElseThrow(() -> ExcelException.fileNotFound(fileId));
        String extension = handle.extension();
        if (!extension.isEmpty() && !List.of("xlsx", "xls", "xlsm").contains(extension)) {
            throw ExcelException.unsupportedFormat(extension);
        }
        limits.checkFileSize(handle.size());
        return handle;
    }

    private WorkbookReader open(WorkflowFileHandle handle) {
        try {
            InputStream content = files.open(handle.fileId());
            return WorkbookReader.open(content, limits);
        } catch (PluginException ex) {
            throw ExcelException.storageError(ex.getMessage(), ex);
        }
    }

    /** Writes bytes as a new workflow file. Never overwrites the input; see WorkflowFileAccess. */
    private WorkflowFileHandle store(byte[] bytes, String fileName, String contentType) {
        try {
            return files.write(fileName, contentType, new ByteArrayInputStream(bytes));
        } catch (PluginException ex) {
            throw ExcelException.storageError(ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ output helpers

    private NodeExecutionResult storedResult(SheetTable table, String fileName) {
        return storedResult(table, fileName, -1, null);
    }

    private NodeExecutionResult storedResult(SheetTable table, String fileName, int affected,
                                             String affectedKey) {
        WorkflowFileHandle stored = store(WorkbookWriter.singleSheet(table), fileName, XLSX_CONTENT_TYPE);
        Map<String, Object> outputs = base(table);
        if (affectedKey != null) {
            outputs.put(affectedKey, affected);
        }
        addFile(outputs, stored);
        return NodeExecutionResult.success(outputs);
    }

    private NodeExecutionResult matchedResult(SheetTable source, SheetTable matched) {
        Map<String, Object> outputs = base(matched);
        outputs.put("matchCount", matched.rowCount());
        outputs.put("searchedRows", source.rowCount());
        outputs.put("data", matched.toMaps());
        return NodeExecutionResult.success(outputs);
    }

    private Map<String, Object> base(SheetTable table) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", true);
        outputs.put("operation", operation.name());
        outputs.put("sheet", table.sheetName());
        outputs.put("rowCount", table.rowCount());
        outputs.put("columnCount", table.columnCount());
        outputs.put("headers", table.headers());
        return outputs;
    }

    private static void addFile(Map<String, Object> outputs, WorkflowFileHandle handle) {
        outputs.put("fileId", handle.fileId());
        outputs.put("fileName", handle.fileName());
        outputs.put("size", handle.size());
        outputs.put("checksum", handle.checksum());
    }

    private String outputName(String fallback) {
        String configured = cfg.getString("outputFileName", null);
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private char quoteChar() {
        String configured = cfg.getString("quoteChar", "\"");
        return configured == null || configured.isEmpty() ? '"' : configured.charAt(0);
    }

    // ------------------------------------------------------------------ configuration readers

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : rawList(key)) {
            if (entry instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            } else {
                throw ExcelException.invalidData("'" + key + "' must be an array of objects.");
            }
        }
        return result;
    }

    private List<String> stringList(String key) {
        List<String> result = new ArrayList<>();
        for (Object entry : rawList(key)) {
            result.add(String.valueOf(entry));
        }
        return result;
    }

    private List<?> rawList(String key) {
        Object raw = cfg.find(key).orElse(null);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list;
        }
        throw ExcelException.invalidData("'" + key + "' must be an array.");
    }

    /**
     * An A1-style cell reference.
     *
     * <p>Parsed here rather than with POI's own {@code CellReference}, because that class also accepts sheet
     * qualifiers and absolute markers ({@code Sheet2!$B$5}) — accepting those would let a node addressing one
     * sheet silently read another.
     */
    record CellReference(int row, int column, String original) {

        static CellReference parse(String reference) {
            if (reference == null || reference.isBlank()) {
                throw ExcelException.invalidData("A cell reference is required, for example B5.");
            }
            String trimmed = reference.trim().toUpperCase(java.util.Locale.ROOT);
            int split = 0;
            while (split < trimmed.length() && Character.isLetter(trimmed.charAt(split))) {
                split++;
            }
            if (split == 0 || split == trimmed.length()) {
                throw ExcelException.invalidData(
                        "'" + reference + "' is not a cell reference. Use a form like B5.");
            }
            String letters = trimmed.substring(0, split);
            int column = 0;
            for (char letter : letters.toCharArray()) {
                if (letter < 'A' || letter > 'Z') {
                    throw ExcelException.invalidData("'" + reference + "' is not a cell reference.");
                }
                column = column * 26 + (letter - 'A' + 1);
            }
            try {
                int row = Integer.parseInt(trimmed.substring(split));
                if (row < 1) {
                    throw ExcelException.invalidData("Cell rows start at 1.");
                }
                return new CellReference(row - 1, column - 1, reference.trim());
            } catch (NumberFormatException ex) {
                throw ExcelException.invalidData(
                        "'" + reference + "' is not a cell reference. Use a form like B5.");
            }
        }
    }

    private static void requireNewName(String name) {
        if (name == null || name.isBlank()) {
            throw ExcelException.invalidData("This operation needs a 'newSheetName'.");
        }
    }

    private static String requireSheetName(String configured, List<String> available) {
        if (configured == null || configured.isBlank()) {
            if (available.isEmpty()) {
                throw ExcelException.invalidFormat("The workbook contains no sheets.");
            }
            return available.get(0);
        }
        return configured;
    }

    private static boolean nameTaken(List<SheetTable> tables, String name) {
        for (SheetTable table : tables) {
            if (table.sheetName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<SheetTable> tables, String name) {
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).sheetName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        List<String> available = new ArrayList<>(tables.size());
        for (SheetTable table : tables) {
            available.add(table.sheetName());
        }
        throw ExcelException.sheetNotFound(name, available);
    }
}
