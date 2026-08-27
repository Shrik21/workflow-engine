package com.orchpilot.plugin.excel;

import com.orchpilot.plugin.excel.model.ExcelOperation;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.List;
import java.util.Map;

/**
 * Each node's configuration schema.
 *
 * <h2>The form changes with the operation because the node does</h2>
 *
 * The designer renders a node's form from the schema it declares, so a per-operation schema is what makes
 * Search show a column, an operator and a value while Append shows a row list. A single node with every field
 * on it would show fifty inputs, most irrelevant to whatever the author is doing. This costs no hand-written
 * UI: it is all declaration.
 *
 * <h2>A file is always a reference, never a path</h2>
 *
 * Every operation that reads a workbook takes a {@code fileId} — the id from a workflow file reference. There
 * is deliberately no path field anywhere in this file, because the plugin has no way to use one: it reaches
 * files only through the engine's execution-scoped accessor.
 */
final class NodeSchemas {

    private NodeSchemas() {
    }

    static Map<String, Object> forOperation(ExcelOperation operation) {
        SchemaBuilder schema = SchemaBuilder.object();

        if (operation.consumesFile()) {
            fileInput(schema);
        }
        if (readsSheet(operation)) {
            sheetInput(schema);
        }

        switch (operation) {
            case READ, EXCEL_TO_JSON -> schema
                    .select("returnType", "Return as", List.of("JSON", "TABLE", "ROWS", "COLUMNS"), false)
                    .withDefault("returnType", "JSON")
                    .withDescription("returnType",
                            "JSON gives one object per row; ROWS gives positional arrays; COLUMNS groups by "
                                    + "column; TABLE gives headers plus rows.");

            case READ_WORKBOOK, SHEET_METADATA -> {
                // Nothing beyond the file (and sheet, for metadata).
            }

            case GET_CELL -> schema.string("cell", "Cell reference", true)
                    .withDescription("cell", "For example B5.");

            case SET_CELL -> schema.string("cell", "Cell reference", true)
                    .string("value", "Value", false)
                    .withDescription("value", "Written as text unless it parses as a number or a date.");

            case SEARCH -> schema
                    .string("column", "Search column", true)
                    .select("operator", "Operator",
                            List.of("EQUALS", "NOT_EQUALS", "CONTAINS", "NOT_CONTAINS", "STARTS_WITH",
                                    "ENDS_WITH", "REGEX", "IS_EMPTY", "IS_NOT_EMPTY"), false)
                    .withDefault("operator", "EQUALS")
                    .string("value", "Value", false)
                    .bool("caseSensitive", "Case sensitive", false)
                    .bool("allowRegex", "Allow regular expressions", false)
                    .withDescription("allowRegex",
                            "Off by default: a careless pattern can backtrack badly enough to hang a thread.");

            case FILTER -> schema
                    .map("conditions", "Conditions", true)
                    .withDescription("conditions",
                            "An array of {column, operator, value, secondValue, caseSensitive}. Operators: "
                                    + "EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN, GREATER_OR_EQUAL, "
                                    + "LESS_OR_EQUAL, CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, BETWEEN, "
                                    + "IS_EMPTY, IS_NOT_EMPTY, REGEX.")
                    .select("combine", "Combine with", List.of("AND", "OR"), false)
                    .withDefault("combine", "AND")
                    .bool("allowRegex", "Allow regular expressions", false);

            case SORT -> schema.map("sortBy", "Sort by", true)
                    .withDescription("sortBy",
                            "An array of {column, order} where order is ASC or DESC. Sorting is stable, so "
                                    + "rows equal on every key keep their original order.");

            case VALIDATE -> schema.map("rules", "Validation rules", true)
                    .withDescription("rules",
                            "An array of {column, type, ...}. Types: REQUIRED, STRING, NUMBER, INTEGER, "
                                    + "DECIMAL, EMAIL, DATE, DATE_RANGE, NUMBER_RANGE, REGEX, ALLOWED_VALUES.")
                    .integer("maxErrors", "Maximum errors reported", false).withDefault("maxErrors", 500)
                    .bool("failOnInvalid", "Fail the node when invalid", false)
                    .withDescription("failOnInvalid",
                            "Off by default: the node reports valid=false and succeeds, so the workflow can "
                                    + "branch. Turn on to stop the workflow instead.");

            case TRANSFORM -> schema.map("steps", "Transform steps", true)
                    .withDescription("steps",
                            "An array of {type, ...} applied in order. Types: RENAME, MAP, REMOVE, ADD, "
                                    + "CONCAT, CONVERT, TRIM, UPPERCASE, LOWERCASE, REPLACE, FORMAT_DATE, "
                                    + "CALCULATE.");

            case COMPARE -> schema
                    .string("compareFileId", "Second file id", true)
                    .string("compareSheet", "Second sheet", false)
                    .string("keyColumn", "Key column", true)
                    .withDescription("keyColumn",
                            "Rows are matched on this column, not by position — one inserted row would "
                                    + "otherwise report every following row as changed.")
                    .map("columns", "Columns to compare", false)
                    .withDescription("columns", "Leave empty to compare every column the two sheets share.")
                    .integer("maxReported", "Maximum differences reported", false)
                    .withDefault("maxReported", 500);

            case GENERATE_REPORT -> schema
                    .map("groupBy", "Group by", false)
                    .withDescription("groupBy", "An array of column names. Empty aggregates the whole sheet.")
                    .map("aggregations", "Aggregations", true)
                    .withDescription("aggregations",
                            "An array of {column, function, alias}. Functions: SUM, COUNT, AVERAGE, MIN, MAX.")
                    .string("outputFileName", "Output file name", false)
                    .withDefault("outputFileName", "report.xlsx");

            case CREATE -> schema
                    .string("fileName", "File name", true).withDefault("fileName", "workbook.xlsx")
                    .string("sheetName", "Sheet name", false).withDefault("sheetName", "Sheet1")
                    .map("columns", "Columns", true)
                    .withDescription("columns", "An array of column names.")
                    .map("data", "Rows", false)
                    .withDescription("data", "An array of arrays, one per row, in column order.");

            case WRITE -> schema
                    .map("data", "Rows", true)
                    .withDescription("data", "An array of JSON objects; their keys become the columns.")
                    .string("outputFileName", "Output file name", false);

            case APPEND -> schema
                    .map("rows", "Rows to append", true)
                    .withDescription("rows",
                            "An array of JSON objects keyed by column name, so column order does not matter.")
                    .string("outputFileName", "Output file name", false);

            case UPDATE -> schema
                    .string("findColumn", "Find column", true)
                    .string("findValue", "Find value", true)
                    .map("updates", "Updates", true)
                    .withDescription("updates",
                            "An object of column name to new value. Row numbers are never needed.")
                    .bool("updateAll", "Update every match", false).withDefault("updateAll", true)
                    .string("outputFileName", "Output file name", false);

            case DELETE_ROW -> schema
                    .string("findColumn", "Find column", true)
                    .string("findValue", "Find value", true)
                    .bool("deleteAll", "Delete every match", false).withDefault("deleteAll", false)
                    .withDescription("deleteAll",
                            "Off by default, so an over-broad match removes one row rather than the sheet.")
                    .string("outputFileName", "Output file name", false);

            case CREATE_SHEET -> schema.string("newSheetName", "New sheet name", true)
                    .string("outputFileName", "Output file name", false);

            case RENAME_SHEET -> schema.string("newSheetName", "New name", true)
                    .string("outputFileName", "Output file name", false);

            case COPY_SHEET -> schema.string("newSheetName", "Copy name", true)
                    .string("outputFileName", "Output file name", false);

            case DELETE_SHEET -> schema.string("outputFileName", "Output file name", false);

            case MERGE -> schema
                    .map("fileIds", "Files to merge", true)
                    .withDescription("fileIds",
                            "An array of file ids. Columns are unioned, so files whose columns differ still "
                                    + "merge; rows align by header name rather than by position.")
                    .string("sheetName", "Sheet to read from each file", false)
                    .string("outputFileName", "Output file name", false)
                    .withDefault("outputFileName", "merged.xlsx");

            case SPLIT -> schema
                    .string("splitColumn", "Split by column", true)
                    .string("fileNamePattern", "File name pattern", false)
                    .withDefault("fileNamePattern", "{value}.xlsx")
                    .withDescription("fileNamePattern", "{value} is replaced by the column value.")
                    .integer("maxFiles", "Maximum files produced", false).withDefault("maxFiles", 100)
                    .withDescription("maxFiles",
                            "A guard: splitting on a column with thousands of distinct values would otherwise "
                                    + "create thousands of files.");

            case JSON_TO_EXCEL -> schema
                    .map("data", "JSON rows", true)
                    .withDescription("data", "An array of objects. Columns are the union of every object's keys.")
                    .string("fileName", "File name", false).withDefault("fileName", "data.xlsx")
                    .string("sheetName", "Sheet name", false).withDefault("sheetName", "Sheet1");

            case CSV_TO_EXCEL -> {
                schema.text("csv", "CSV content", true)
                        .string("fileName", "File name", false).withDefault("fileName", "data.xlsx")
                        .string("sheetName", "Sheet name", false).withDefault("sheetName", "Sheet1");
                csvOptions(schema);
            }

            case EXCEL_TO_CSV -> {
                schema.string("outputFileName", "Output file name", false)
                        .withDefault("outputFileName", "export.csv")
                        .bool("returnContent", "Return the CSV as text too", false)
                        .withDescription("returnContent",
                                "Adds the CSV to the node output as well as storing it as a file. Leave off "
                                        + "for large exports.");
                csvOptions(schema);
            }
        }

        if (operation.producesFile()) {
            schema.bool("createNewVersion", "Store as a new file", false)
                    .withDefault("createNewVersion", true)
                    .withDescription("createNewVersion",
                            "Always on: this plugin never overwrites its input. The original file stays "
                                    + "readable, so a retry operates on what it started from.");
        }

        limits(schema);
        return schema.build();
    }

    // ------------------------------------------------------------------ shared blocks

    private static void fileInput(SchemaBuilder schema) {
        schema.string("fileId", "Excel file", true)
                .withDescription("fileId",
                        "The id of a file attached to this workflow version — from an upload, a form node, or "
                                + "a previous node's output. A filesystem path is never accepted.");
    }

    private static void sheetInput(SchemaBuilder schema) {
        schema.string("sheetName", "Sheet", false)
                .withDescription("sheetName", "Blank uses the first sheet.")
                .integer("headerRow", "Header row", false).withDefault("headerRow", -1)
                .withDescription("headerRow",
                        "-1 detects the header automatically, 0 means the sheet has none, or give the "
                                + "1-based row number.")
                .integer("startRow", "First data row", false)
                .integer("endRow", "Last data row", false)
                .bool("readFormulas", "Read formulas as text", false)
                .withDescription("readFormulas",
                        "Off returns each formula's calculated value, which is almost always what a workflow "
                                + "wants. On returns the formula text instead.")
                // The sheet and its header are everyday choices; a row window and the formula mode are things
                // a particular workbook occasionally needs, so they sit behind the toggle.
                .advanced("startRow", "endRow", "readFormulas");
    }

    private static void csvOptions(SchemaBuilder schema) {
        schema.string("delimiter", "Delimiter", false).withDefault("delimiter", ",")
                .withDescription("delimiter", "A single character, or one of COMMA, TAB, SEMICOLON, PIPE.")
                .string("quoteChar", "Quote character", false).withDefault("quoteChar", "\"")
                .bool("includeHeader", "Include header row", false).withDefault("includeHeader", true);
    }

    /**
     * The safety bounds every operation carries.
     *
     * <p>Marked advanced: they apply to every operation and are correct as they stand, so rendering four
     * number boxes above the fold on every Excel node would bury the two or three fields an author is
     * actually there to set.
     */
    private static void limits(SchemaBuilder schema) {
        schema.integer("maxRows", "Maximum rows", false).withDefault("maxRows", 100000)
                .integer("maxColumns", "Maximum columns", false).withDefault("maxColumns", 512)
                .integer("maxFileBytes", "Maximum file size (bytes)", false)
                .withDefault("maxFileBytes", 33554432)
                .integer("maxProcessingSeconds", "Processing timeout (seconds)", false)
                .withDefault("maxProcessingSeconds", 120)
                .advanced("maxRows", "maxColumns", "maxFileBytes", "maxProcessingSeconds");
    }

    /** @return whether the operation reads a sheet's rows, and so needs header and range options */
    private static boolean readsSheet(ExcelOperation operation) {
        return switch (operation) {
            case READ_WORKBOOK, CREATE, JSON_TO_EXCEL, CSV_TO_EXCEL, MERGE -> false;
            default -> true;
        };
    }
}
