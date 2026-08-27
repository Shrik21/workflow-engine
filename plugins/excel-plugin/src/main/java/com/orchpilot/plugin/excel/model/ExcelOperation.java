package com.orchpilot.plugin.excel.model;

/**
 * Every capability this plugin exposes, one node type each.
 *
 * <h2>One node per operation, not one node with a dropdown</h2>
 *
 * A single "Excel Handler" node carrying an operation field would have one risk flag for everything, so the AI
 * Agent could not tell reading a sheet from deleting one, and the designer could not show only the fields that
 * apply. Per-operation node types cost no hand-written UI — each renders from its declared schema — and are
 * what let a delete be marked destructive while a read is not.
 *
 * <h2>Risk drives approval</h2>
 *
 * The platform's node contract carries a boolean {@code destructive}, which is what a supervised agent's
 * approval policy keys on. The finer grade is kept here and published in the manifest, because "delete a row"
 * and "delete a sheet" deserve different policy even though both need a human.
 */
public enum ExcelOperation {

    // ================================================================ reading
    READ("EXCEL_READ", "Read Excel Sheet", "excel.sheet.read",
            "Reads a sheet into JSON, rows, columns or a table.", Risk.READ_ONLY),
    READ_WORKBOOK("EXCEL_READ_WORKBOOK", "Read Excel Workbook", "excel.workbook.listSheets",
            "Lists every sheet in a workbook with its row and column counts.", Risk.READ_ONLY),
    SHEET_METADATA("EXCEL_SHEET_METADATA", "Get Excel Sheet Metadata", "excel.sheet.metadata",
            "Reads one sheet's headers, row count and column count without reading its data.",
            Risk.READ_ONLY),
    GET_CELL("EXCEL_GET_CELL", "Get Excel Cell", "excel.cell.get",
            "Reads a single cell by reference, e.g. B5, with its detected type.", Risk.READ_ONLY),

    // ================================================================ searching and shaping
    SEARCH("EXCEL_SEARCH", "Search Excel", "excel.search",
            "Finds rows where a column matches by equality, contains, prefix, suffix or regex.",
            Risk.READ_ONLY),
    FILTER("EXCEL_FILTER", "Filter Excel", "excel.filter",
            "Filters rows on multiple conditions combined with AND or OR.", Risk.READ_ONLY),
    SORT("EXCEL_SORT", "Sort Excel", "excel.sort",
            "Sorts rows by one or more columns, ascending or descending.", Risk.READ_ONLY),
    VALIDATE("EXCEL_VALIDATE", "Validate Excel", "excel.validate",
            "Checks rows against typed rules and reports every failure with its row and column.",
            Risk.READ_ONLY),
    TRANSFORM("EXCEL_TRANSFORM", "Transform Excel", "excel.transform",
            "Renames, maps, adds, removes and converts columns, and derives new ones.", Risk.LOW),
    COMPARE("EXCEL_COMPARE", "Compare Excel Files", "excel.compare",
            "Compares two sheets by key column and reports added, removed, changed and unchanged rows.",
            Risk.READ_ONLY),
    GENERATE_REPORT("EXCEL_GENERATE_REPORT", "Generate Excel Report", "excel.report",
            "Groups rows by one or more columns and aggregates with SUM, COUNT, AVERAGE, MIN or MAX.",
            Risk.LOW),

    // ================================================================ writing
    CREATE("EXCEL_CREATE", "Create Excel File", "excel.create",
            "Creates a new workbook from columns and rows, and stores it as a workflow file.", Risk.MEDIUM),
    WRITE("EXCEL_WRITE", "Write Excel Sheet", "excel.sheet.write",
            "Replaces a sheet's contents, writing the result as a new workflow file.", Risk.MEDIUM),
    APPEND("EXCEL_APPEND", "Append Excel Rows", "excel.row.add",
            "Adds one or more rows to the end of a sheet.", Risk.MEDIUM),
    UPDATE("EXCEL_UPDATE", "Update Excel Rows", "excel.row.update",
            "Finds rows by a column value and updates other columns, without needing a row number.",
            Risk.MEDIUM),
    SET_CELL("EXCEL_SET_CELL", "Set Excel Cell", "excel.cell.set",
            "Writes a single cell by reference.", Risk.MEDIUM),
    DELETE_ROW("EXCEL_DELETE_ROW", "Delete Excel Rows", "excel.row.delete",
            "Removes rows matching a column value. Irreversible in the produced file.", Risk.HIGH),

    // ================================================================ sheets
    CREATE_SHEET("EXCEL_CREATE_SHEET", "Create Excel Sheet", "excel.sheet.create",
            "Adds a new sheet to a workbook.", Risk.MEDIUM),
    RENAME_SHEET("EXCEL_RENAME_SHEET", "Rename Excel Sheet", "excel.sheet.rename",
            "Renames a sheet.", Risk.MEDIUM),
    COPY_SHEET("EXCEL_COPY_SHEET", "Copy Excel Sheet", "excel.sheet.copy",
            "Duplicates a sheet under a new name.", Risk.MEDIUM),
    DELETE_SHEET("EXCEL_DELETE_SHEET", "Delete Excel Sheet", "excel.sheet.delete",
            "Removes a sheet and everything on it. Irreversible in the produced file.", Risk.HIGH),

    // ================================================================ whole-file operations
    MERGE("EXCEL_MERGE", "Merge Excel Files", "excel.merge",
            "Combines several workbooks into one, unioning columns where they differ.", Risk.MEDIUM),
    SPLIT("EXCEL_SPLIT", "Split Excel File", "excel.split",
            "Splits one sheet into a workbook per distinct value of a column.", Risk.MEDIUM),

    // ================================================================ conversion
    EXCEL_TO_JSON("EXCEL_TO_JSON", "Excel To JSON", "excel.toJson",
            "Converts a sheet to a JSON array, detecting types and headers.", Risk.READ_ONLY),
    JSON_TO_EXCEL("EXCEL_JSON_TO_EXCEL", "JSON To Excel", "excel.fromJson",
            "Builds a workbook from a JSON array of objects.", Risk.MEDIUM),
    EXCEL_TO_CSV("EXCEL_TO_CSV", "Excel To CSV", "excel.toCsv",
            "Exports a sheet as CSV with a configurable delimiter and quoting.", Risk.LOW),
    CSV_TO_EXCEL("EXCEL_CSV_TO_EXCEL", "CSV To Excel", "excel.fromCsv",
            "Builds a workbook from CSV content.", Risk.MEDIUM);

    /** Coarse risk, published in the manifest and mapped onto the node's {@code destructive} flag. */
    public enum Risk {
        READ_ONLY,
        LOW,
        MEDIUM,
        HIGH
    }

    private final String nodeType;
    private final String displayName;
    private final String capability;
    private final String description;
    private final Risk risk;

    ExcelOperation(String nodeType, String displayName, String capability, String description, Risk risk) {
        this.nodeType = nodeType;
        this.displayName = displayName;
        this.capability = capability;
        this.description = description;
        this.risk = risk;
    }

    public String nodeType() {
        return nodeType;
    }

    public String displayName() {
        return displayName;
    }

    public String capability() {
        return capability;
    }

    public String description() {
        return description;
    }

    public Risk risk() {
        return risk;
    }

    /** @return whether a supervised agent must have this approved */
    public boolean destructive() {
        return risk == Risk.HIGH;
    }

    /**
     * @return whether the operation produces a new stored file
     */
    public boolean producesFile() {
        return switch (this) {
            case READ, READ_WORKBOOK, SHEET_METADATA, GET_CELL, SEARCH, FILTER, SORT, VALIDATE, COMPARE,
                 EXCEL_TO_JSON -> false;
            default -> true;
        };
    }

    /** @return whether the operation reads an existing workflow file as its input */
    public boolean consumesFile() {
        return switch (this) {
            case CREATE, JSON_TO_EXCEL, CSV_TO_EXCEL, MERGE -> false;
            default -> true;
        };
    }

    public static ExcelOperation forNodeType(String nodeType) {
        for (ExcelOperation operation : values()) {
            if (operation.nodeType.equals(nodeType)) {
                return operation;
            }
        }
        return null;
    }
}
