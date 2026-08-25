package com.orchpilot.plugin.excel;

import com.orchpilot.plugin.excel.io.WorkbookReader;
import com.orchpilot.plugin.excel.model.ExcelLimits;
import com.orchpilot.plugin.excel.model.ExcelOperation;
import com.orchpilot.plugin.excel.model.SheetTable;
import com.orchpilot.plugin.excel.support.FakeFileAccess;
import com.orchpilot.plugin.excel.support.Workbooks;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The plugin end to end, over an in-memory file store.
 *
 * <p>Every operation goes through the real reader, the real engines and the real writer — only the file store
 * is a stand-in. That means a test asserting "append added a row" actually round-trips a workbook through POI
 * and reads it back, which is the only way to catch a write that produces a file Excel would refuse.
 */
class ExcelPluginTest {

    private ExcelPlugin plugin;
    private FakeFileAccess files;
    private String employeesId;

    @BeforeEach
    void setUp() {
        files = new FakeFileAccess();
        employeesId = files.seed("employees.xlsx", Workbooks.employees());

        PluginContext context = mock(PluginContext.class);
        lenient().when(context.logger()).thenReturn(mock(PluginLogger.class));
        lenient().when(context.dataStore()).thenReturn(mock(PluginDataStore.class));

        plugin = new ExcelPlugin();
        plugin.initialize(context);
    }

    // ------------------------------------------------------------------ catalogue

    @Test
    @DisplayName("every operation is published with its risk flag")
    void catalogue() {
        List<NodeDefinition> definitions = plugin.getNodeDefinitions();

        assertThat(definitions).hasSize(ExcelOperation.values().length);
        assertThat(definitions).allMatch(NodeDefinition::supportsAI);

        // The approval engine keys off destructive, so these are the contract.
        assertThat(definition(definitions, "EXCEL_DELETE_ROW").destructive()).isTrue();
        assertThat(definition(definitions, "EXCEL_DELETE_SHEET").destructive()).isTrue();
        assertThat(definition(definitions, "EXCEL_READ").destructive()).isFalse();

        // A read is repeatable; anything that stores a file is not, or a retry would leave two copies.
        assertThat(definition(definitions, "EXCEL_READ").idempotent()).isTrue();
        assertThat(definition(definitions, "EXCEL_CREATE").idempotent()).isFalse();
    }

    // ------------------------------------------------------------------ reading

    @Test
    @DisplayName("reads a sheet as JSON with detected types")
    void readsAsJson() {
        NodeExecutionResult result = run("EXCEL_READ", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("rowCount", 3).containsEntry("columnCount", 6);

        List<?> data = (List<?>) result.outputs().get("data");
        assertThat(map(data.get(0)))
                .containsEntry("Name", "John")
                .containsEntry("Salary", 50000L)
                .containsEntry("HireDate", "2021-03-01");
    }

    @Test
    @DisplayName("returns rows and columns shapes on request")
    void alternativeReturnShapes() {
        NodeExecutionResult rows = run("EXCEL_READ", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "returnType", "ROWS"));
        assertThat((List<?>) rows.outputs().get("data")).hasSize(3);
        assertThat((List<?>) ((List<?>) rows.outputs().get("data")).get(0)).hasSize(6);

        NodeExecutionResult columns = run("EXCEL_READ", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "returnType", "COLUMNS"));
        assertThat(map(columns.outputs().get("data"))).containsKey("Department");
    }

    @Test
    @DisplayName("lists a workbook's sheets")
    void readsWorkbook() {
        String id = files.seed("multi.xlsx", Workbooks.multiSheet());

        NodeExecutionResult result = run("EXCEL_READ_WORKBOOK", config("fileId", id));

        assertThat(result.outputs()).containsEntry("sheetCount", 3);
        assertThat(list(result.outputs().get("sheets"))).hasSize(3);
    }

    @Test
    @DisplayName("reads a single cell with its type")
    void readsACell() {
        NodeExecutionResult result = run("EXCEL_GET_CELL", config("fileId", employeesId,
                "sheetName", "Employees", "cell", "B2"));

        assertThat(result.outputs()).containsEntry("cell", "B2")
                .containsEntry("value", "John")
                .containsEntry("type", "STRING");
    }

    // ------------------------------------------------------------------ shaping

    @Test
    @DisplayName("searches a column")
    void searches() {
        NodeExecutionResult result = run("EXCEL_SEARCH", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "column", "Department", "value", "IT"));

        assertThat(result.outputs()).containsEntry("matchCount", 2).containsEntry("searchedRows", 3);
    }

    @Test
    @DisplayName("filters on multiple conditions combined with AND")
    void filters() {
        NodeExecutionResult result = run("EXCEL_FILTER", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "conditions", List.of(
                        Map.of("column", "Department", "operator", "EQUALS", "value", "IT"),
                        Map.of("column", "Salary", "operator", "GREATER_THAN", "value", "60000")),
                "combine", "AND"));

        // Only Raj is in IT and above 60000.
        assertThat(result.outputs()).containsEntry("matchCount", 1);
        assertThat(map(list(result.outputs().get("data")).get(0)))
                .containsEntry("Name", "Raj");
    }

    @Test
    @DisplayName("regex is refused unless the node enables it")
    void regexIsOptIn() {
        NodeExecutionResult refused = run("EXCEL_SEARCH", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "column", "Name", "operator", "REGEX", "value", "^J.*"));

        assertThat(refused.isSuccess()).isFalse();
        assertThat(refused.errorMessage()).contains("allowRegex");

        NodeExecutionResult allowed = run("EXCEL_SEARCH", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "column", "Name", "operator", "REGEX", "value", "^J.*", "allowRegex", true));
        assertThat(allowed.outputs()).containsEntry("matchCount", 1);
    }

    @Test
    @DisplayName("sorts descending by a numeric column")
    void sorts() {
        NodeExecutionResult result = run("EXCEL_SORT", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "sortBy", List.of(Map.of("column", "Salary", "order", "DESC"))));

        List<?> data = (List<?>) result.outputs().get("data");
        assertThat(map(data.get(0))).containsEntry("Salary", 75000L);
        assertThat(map(data.get(2))).containsEntry("Salary", 50000L);
    }

    @Test
    @DisplayName("validation reports every failure and still succeeds")
    void validates() {
        NodeExecutionResult result = run("EXCEL_VALIDATE", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "rules", List.of(
                        Map.of("column", "Salary", "type", "NUMBER_RANGE", "min", 60000),
                        Map.of("column", "Department", "type", "ALLOWED_VALUES",
                                "allowedValues", List.of("HR", "Finance")))));

        // The node did its job, so it succeeds; the data is what is invalid.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("valid", false);

        List<?> errors = (List<?>) result.outputs().get("errors");
        assertThat(errors).isNotEmpty();
        // Row numbers count the header, so they match what the user sees in Excel.
        assertThat(map(errors.get(0))).containsEntry("row", 2).containsEntry("column", "Salary");
    }

    @Test
    @DisplayName("failOnInvalid turns a validation failure into a node failure")
    void validationCanFailTheNode() {
        NodeExecutionResult result = run("EXCEL_VALIDATE", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "failOnInvalid", true,
                "rules", List.of(Map.of("column", "Salary", "type", "NUMBER_RANGE", "min", 100000))));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXCEL_VALIDATION_FAILED");
    }

    @Test
    @DisplayName("transforms columns and derives a calculated one")
    void transforms() {
        NodeExecutionResult result = run("EXCEL_TRANSFORM", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "steps", List.of(
                        Map.of("type", "RENAME", "from", "Name", "to", "FullName"),
                        Map.of("type", "CALCULATE", "column", "Salary", "target", "AnnualSalary",
                                "operator", "*", "operand", 12))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(list(result.outputs().get("headers"))).contains("FullName", "AnnualSalary");
        assertThat(map(list(result.outputs().get("data")).get(0)))
                .containsEntry("AnnualSalary", 600000L);
    }

    @Test
    @DisplayName("groups and aggregates into a report")
    void generatesAReport() {
        NodeExecutionResult result = run("EXCEL_GENERATE_REPORT", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "groupBy", List.of("Department"),
                "aggregations", List.of(
                        Map.of("column", "Salary", "function", "SUM", "alias", "TotalSalary"),
                        Map.of("function", "COUNT", "alias", "Headcount"))));

        assertThat(result.isSuccess()).isTrue();
        List<?> data = (List<?>) result.outputs().get("data");
        assertThat(data).hasSize(2);
        assertThat(map(data.get(0)))
                .containsEntry("Department", "IT")
                .containsEntry("TotalSalary", 125000L)
                .containsEntry("Headcount", 2L);
        // A report is a file too, so it can be emailed by a later node.
        assertThat(result.outputs()).containsKey("fileId");
    }

    // ------------------------------------------------------------------ writing

    @Test
    @DisplayName("creates a workbook and stores it as a new file")
    void creates() {
        NodeExecutionResult result = run("EXCEL_CREATE", config(
                "fileName", "new.xlsx", "sheetName", "People",
                "columns", List.of("Name", "Email", "Department"),
                "data", List.of(
                        List.of("John", "john@test.com", "IT"),
                        List.of("Sarah", "sarah@test.com", "HR"))));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("fileName", "new.xlsx").containsEntry("rowCount", 2);

        // Round-trip: the produced bytes must be a workbook POI can read back.
        SheetTable written = readBack(String.valueOf(result.outputs().get("fileId")));
        assertThat(written.sheetName()).isEqualTo("People");
        assertThat(written.headers()).containsExactly("Name", "Email", "Department");
        assertThat(written.toMaps().get(1)).containsEntry("Name", "Sarah");
    }

    @Test
    @DisplayName("appends rows keyed by column name, so column order does not matter")
    void appends() {
        NodeExecutionResult result = run("EXCEL_APPEND", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                // Deliberately not in the sheet's column order.
                "rows", List.of(new LinkedHashMap<>(Map.of(
                        "Department", "Finance", "Name", "Mia", "EmployeeId", "004")))));

        assertThat(result.outputs()).containsEntry("appendedRows", 1).containsEntry("rowCount", 4);

        SheetTable written = readBack(String.valueOf(result.outputs().get("fileId")));
        Map<String, Object> appended = written.toMaps().get(3);
        assertThat(appended).containsEntry("Name", "Mia").containsEntry("Department", "Finance");
    }

    @Test
    @DisplayName("updates rows found by a column value, without a row number")
    void updates() {
        NodeExecutionResult result = run("EXCEL_UPDATE", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "EmployeeId", "findValue", "001",
                "updates", Map.of("Salary", 75000)));

        assertThat(result.outputs()).containsEntry("updatedRows", 1);

        SheetTable written = readBack(String.valueOf(result.outputs().get("fileId")));
        assertThat(written.toMaps().get(0)).containsEntry("Salary", 75000L);
    }

    @Test
    @DisplayName("an update matching nothing fails rather than silently doing nothing")
    void updateWithNoMatchFails() {
        NodeExecutionResult result = run("EXCEL_UPDATE", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "EmployeeId", "findValue", "999",
                "updates", Map.of("Salary", 1)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXCEL_ROW_NOT_FOUND");
    }

    @Test
    @DisplayName("deletes one matching row by default, not every match")
    void deletesOneRowByDefault() {
        NodeExecutionResult result = run("EXCEL_DELETE_ROW", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "Department", "findValue", "IT"));

        // Two rows are in IT; the safe default removes one.
        assertThat(result.outputs()).containsEntry("deletedRows", 1).containsEntry("rowCount", 2);
    }

    @Test
    @DisplayName("deleteAll removes every match")
    void deletesAllMatches() {
        NodeExecutionResult result = run("EXCEL_DELETE_ROW", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "Department", "findValue", "IT", "deleteAll", true));

        assertThat(result.outputs()).containsEntry("deletedRows", 2).containsEntry("rowCount", 1);
    }

    @Test
    @DisplayName("writing never overwrites the input file")
    void neverOverwritesTheInput() {
        byte[] before = files.bytesOf(employeesId);

        NodeExecutionResult result = run("EXCEL_UPDATE", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "EmployeeId", "findValue", "001", "updates", Map.of("Salary", 1)));

        // The original is byte-identical and the result is a different id, so a retry starts from the same
        // input and the evidence of what the workflow began with survives.
        assertThat(files.bytesOf(employeesId)).isEqualTo(before);
        assertThat(result.outputs().get("fileId")).isNotEqualTo(employeesId);
    }

    // ------------------------------------------------------------------ sheets and whole files

    @Test
    @DisplayName("renames a sheet, leaving the others alone")
    void renamesASheet() {
        String id = files.seed("multi.xlsx", Workbooks.multiSheet());

        NodeExecutionResult result = run("EXCEL_RENAME_SHEET", config("fileId", id,
                "sheetName", "Employees", "newSheetName", "Staff"));

        assertThat(list(result.outputs().get("sheets"))).containsExactly("Staff", "Departments", "Archive");
    }

    @Test
    @DisplayName("refuses to delete a workbook's only sheet")
    void refusesToEmptyAWorkbook() {
        NodeExecutionResult result = run("EXCEL_DELETE_SHEET", config("fileId", employeesId,
                "sheetName", "Employees"));

        // Excel will not open a workbook with no sheets, so producing one would be a file nobody can use.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).contains("at least one sheet");
    }

    @Test
    @DisplayName("merges files, unioning columns that differ")
    void merges() {
        String other = files.seed("more.xlsx", Workbooks.employeesXls());

        NodeExecutionResult result = run("EXCEL_MERGE", config(
                "fileIds", List.of(employeesId, other),
                "sheetName", "Employees", "headerRow", 1));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.outputs()).containsEntry("rowCount", 4).containsEntry("mergedFiles", 2);
        // The second file lacks HireDate; the union keeps it and blanks it for those rows.
        assertThat(list(result.outputs().get("headers"))).contains("HireDate");
    }

    @Test
    @DisplayName("splits into one file per distinct value")
    void splits() {
        NodeExecutionResult result = run("EXCEL_SPLIT", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "splitColumn", "Department"));

        assertThat(result.outputs()).containsEntry("fileCount", 2);
        List<?> produced = (List<?>) result.outputs().get("files");
        assertThat(map(produced.get(0))).containsEntry("value", "IT").containsEntry("rowCount", 2);
    }

    @Test
    @DisplayName("a split that would produce too many files is refused")
    void splitIsBounded() {
        NodeExecutionResult result = run("EXCEL_SPLIT", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "splitColumn", "EmployeeId", "maxFiles", 2));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).contains("maxFiles");
    }

    @Test
    @DisplayName("compares two files by key and classifies every row")
    void compares() {
        String changed = files.seed("changed.xlsx", Workbooks.employees());
        NodeExecutionResult update = run("EXCEL_UPDATE", config("fileId", changed,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "EmployeeId", "findValue", "002", "updates", Map.of("Salary", 99000)));

        NodeExecutionResult result = run("EXCEL_COMPARE", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1,
                "compareFileId", String.valueOf(update.outputs().get("fileId")),
                "keyColumn", "EmployeeId"));

        assertThat(result.outputs()).containsEntry("added", 0).containsEntry("removed", 0)
                .containsEntry("changed", 1).containsEntry("unchanged", 2);
    }

    // ------------------------------------------------------------------ conversion

    @Test
    @DisplayName("round-trips Excel to CSV and back")
    void csvRoundTrip() {
        NodeExecutionResult toCsv = run("EXCEL_TO_CSV", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "returnContent", true));

        String csv = String.valueOf(toCsv.outputs().get("csv"));
        assertThat(csv).contains("EmployeeId,Name,Department").contains("John");

        NodeExecutionResult back = run("EXCEL_CSV_TO_EXCEL", config("csv", csv, "fileName", "back.xlsx"));
        assertThat(back.outputs()).containsEntry("rowCount", 3);
    }

    @Test
    @DisplayName("a CSV value starting with = is neutralised so opening the file cannot execute it")
    void csvFormulaInjection() {
        String id = files.seed("evil.xlsx", Workbooks.employees());
        NodeExecutionResult injected = run("EXCEL_UPDATE", config("fileId", id,
                "sheetName", "Employees", "headerRow", 1,
                "findColumn", "EmployeeId", "findValue", "001",
                "updates", Map.of("Name", "=cmd|'/c calc'!A1")));

        NodeExecutionResult csv = run("EXCEL_TO_CSV", config(
                "fileId", String.valueOf(injected.outputs().get("fileId")),
                "sheetName", "Employees", "headerRow", 1, "returnContent", true));

        String content = String.valueOf(csv.outputs().get("csv"));
        // Prefixed with an apostrophe: the content survives, and a spreadsheet treats it as text.
        assertThat(content).contains("'=cmd");
        assertThat(content).doesNotContain(",=cmd");
    }

    @Test
    @DisplayName("builds a workbook from JSON, unioning every object's keys")
    void jsonToExcel() {
        NodeExecutionResult result = run("EXCEL_JSON_TO_EXCEL", config(
                "fileName", "people.xlsx",
                "data", List.of(
                        new LinkedHashMap<>(Map.of("name", "John", "age", 30)),
                        // Second object has an extra key, which must not be lost.
                        new LinkedHashMap<>(Map.of("name", "Sarah", "age", 28, "city", "Pune")))));

        assertThat(result.isSuccess()).isTrue();
        SheetTable written = readBack(String.valueOf(result.outputs().get("fileId")));
        assertThat(written.headers()).contains("name", "age", "city");
        assertThat(written.toMaps().get(0)).containsEntry("age", 30L);
    }

    // ------------------------------------------------------------------ errors

    @Test
    @DisplayName("an unknown file id fails with a stable code")
    void unknownFile() {
        NodeExecutionResult result = run("EXCEL_READ", config("fileId", "FILE-nope"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXCEL_FILE_NOT_FOUND");
    }

    @Test
    @DisplayName("a non-Excel extension is refused before the file is opened")
    void unsupportedFormat() {
        String id = files.seed("notes.txt", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        NodeExecutionResult result = run("EXCEL_READ", config("fileId", id));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXCEL_UNSUPPORTED_FORMAT");
    }

    @Test
    @DisplayName("a missing column names the ones that exist")
    void missingColumn() {
        NodeExecutionResult result = run("EXCEL_SEARCH", config("fileId", employeesId,
                "sheetName", "Employees", "headerRow", 1, "column", "Nope", "value", "x"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EXCEL_COLUMN_NOT_FOUND");
        assertThat(result.errorMessage()).contains("Department");
    }

    // ------------------------------------------------------------------ helpers

    private SheetTable readBack(String fileId) {
        try (WorkbookReader reader = WorkbookReader.open(
                new ByteArrayInputStream(files.bytesOf(fileId)), ExcelLimits.DEFAULTS)) {
            return reader.read(null, 1, 0, 0, false);
        }
    }

    private NodeExecutionResult run(String nodeType, Map<String, Object> configuration) {
        NodeExecutionContext context = mock(NodeExecutionContext.class);
        lenient().when(context.nodeType()).thenReturn(nodeType);
        lenient().when(context.configuration()).thenReturn(new MapConfiguration(configuration));
        lenient().when(context.files()).thenReturn(files);
        lenient().when(context.executionId()).thenReturn("exec-1");
        lenient().when(context.workflowId()).thenReturn("WF-100");
        lenient().when(context.workflowVersion()).thenReturn(1);
        lenient().when(context.nodeId()).thenReturn("node-1");
        lenient().when(context.attempt()).thenReturn(1);
        lenient().when(context.currentUser()).thenReturn(Optional.empty());
        return plugin.execute(context);
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            configuration.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return configuration;
    }

    /** Casts an output list to a concrete element type — {@code List<?>} defeats AssertJ's varargs matchers. */
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    /** Likewise for maps: a wildcard capture makes {@code containsEntry} uncallable. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static NodeDefinition definition(List<NodeDefinition> definitions, String nodeType) {
        return definitions.stream().filter(definition -> definition.nodeType().equals(nodeType))
                .findFirst().orElseThrow(() -> new AssertionError("No node definition for " + nodeType));
    }
}
