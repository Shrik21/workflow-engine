package com.orchpilot.plugin.excel;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.io.WorkbookReader;
import com.orchpilot.plugin.excel.model.CellType;
import com.orchpilot.plugin.excel.model.ExcelLimits;
import com.orchpilot.plugin.excel.model.SheetTable;
import com.orchpilot.plugin.excel.support.Workbooks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading real workbooks: type narrowing, header handling, formats and the limits.
 *
 * <p>The type assertions carry most of the weight. Excel stores every number as a double and marks a date only
 * by its format, so "reads the file" is easy and "reports 50000 as an integer and 2021-03-01 as a date" is
 * where a reader is actually right or wrong.
 */
class WorkbookReaderTest {

    private static final ExcelLimits LIMITS = ExcelLimits.DEFAULTS;

    private SheetTable read(byte[] workbook, String sheet, int headerRow) {
        try (WorkbookReader reader = WorkbookReader.open(new ByteArrayInputStream(workbook), LIMITS)) {
            return reader.read(sheet, headerRow, 0, 0, false);
        }
    }

    // ------------------------------------------------------------------ types

    @Test
    @DisplayName("narrows numbers, dates and booleans instead of reporting everything as a double")
    void narrowsTypes() {
        SheetTable table = read(Workbooks.employees(), "Employees", 1);

        assertThat(table.headers())
                .containsExactly("EmployeeId", "Name", "Department", "Salary", "HireDate", "Active");
        assertThat(table.rowCount()).isEqualTo(3);

        List<com.orchpilot.plugin.excel.model.CellValue> first = table.rows().get(0);
        // A salary Excel stored as 50000.0 must not reach a workflow as "50000.0".
        assertThat(table.cell(first, "Salary").type()).isEqualTo(CellType.INTEGER);
        assertThat(table.cell(first, "Salary").toJson()).isEqualTo(50000L);
        // A date is a formatted number in the file; reporting it as 44256 would be useless.
        assertThat(table.cell(first, "HireDate").type()).isEqualTo(CellType.DATE);
        assertThat(table.cell(first, "HireDate").toJson()).isEqualTo("2021-03-01");
        assertThat(table.cell(first, "Active").type()).isEqualTo(CellType.BOOLEAN);
        assertThat(table.cell(first, "Name").type()).isEqualTo(CellType.STRING);
    }

    @Test
    @DisplayName("dates come out in a consistent ISO format")
    void datesAreIso() {
        SheetTable table = read(Workbooks.employees(), "Employees", 1);

        for (List<com.orchpilot.plugin.excel.model.CellValue> row : table.rows()) {
            assertThat(String.valueOf(table.cell(row, "HireDate").toJson()))
                    .matches("\\d{4}-\\d{2}-\\d{2}");
        }
    }

    @Test
    @DisplayName("JSON output is one object per row, keyed by header")
    void jsonShape() {
        SheetTable table = read(Workbooks.employees(), "Employees", 1);

        List<Map<String, Object>> maps = table.toMaps();
        assertThat(maps).hasSize(3);
        assertThat(maps.get(0))
                .containsEntry("EmployeeId", "001")
                .containsEntry("Name", "John")
                .containsEntry("Department", "IT")
                .containsEntry("Salary", 50000L);
    }

    // ------------------------------------------------------------------ formulas

    @Test
    @DisplayName("returns a formula's calculated value by default")
    void readsCalculatedValues() {
        SheetTable table = read(Workbooks.withFormula(), "Totals", 1);

        var total = table.cell(table.rows().get(0), "Total");
        assertThat(total.toJson()).isEqualTo(200L);
        // The formula travels alongside the value, so an output can show both.
        assertThat(total.formula()).isEqualTo("B2*2");
    }

    @Test
    @DisplayName("returns formula text when asked, and evaluates nothing")
    void readsFormulaText() {
        try (WorkbookReader reader =
                     WorkbookReader.open(new ByteArrayInputStream(Workbooks.withFormula()), LIMITS)) {
            SheetTable table = reader.read("Totals", 1, 0, 0, true);

            var total = table.cell(table.rows().get(0), "Total");
            assertThat(total.type()).isEqualTo(CellType.FORMULA);
            assertThat(total.asText()).isEqualTo("=B2*2");
        }
    }

    @Test
    @DisplayName("a blank cell is blank, not zero or an empty string masquerading as data")
    void blankCells() {
        SheetTable table = read(Workbooks.withFormula(), "Totals", 1);

        var amount = table.cell(table.rows().get(1), "Amount");
        assertThat(amount.isBlank()).isTrue();
        assertThat(amount.toJson()).isNull();
    }

    // ------------------------------------------------------------------ headers

    @Test
    @DisplayName("detects a header that follows a title and a blank row")
    void detectsHeaderAfterPreamble() {
        // The case that makes detection worth having: a real exported report never starts at A1.
        SheetTable table = read(Workbooks.withPreamble(), "Report", -1);

        assertThat(table.headers()).containsExactly("Name", "Department", "Salary");
        assertThat(table.rowCount()).isEqualTo(2);
        assertThat(table.toMaps().get(0)).containsEntry("Name", "John");
    }

    @Test
    @DisplayName("an explicit header row overrides detection")
    void explicitHeaderRow() {
        SheetTable table = read(Workbooks.withPreamble(), "Report", 3);

        assertThat(table.headers()).containsExactly("Name", "Department", "Salary");
    }

    @Test
    @DisplayName("headerRow 0 means the sheet has none, and synthetic names are used")
    void noHeader() {
        SheetTable table = read(Workbooks.employees(), "Employees", 0);

        assertThat(table.headers()).startsWith("Column1", "Column2");
        // The real header row is data in this mode, so nothing is silently eaten.
        assertThat(table.rowCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("duplicate headers are made addressable rather than rejected")
    void duplicateHeaders() {
        SheetTable table = read(Workbooks.duplicateHeaders(), "Sheet1", 1);

        assertThat(table.headers()).containsExactly("Name", "Name_2", "Value");
        assertThat(table.toMaps().get(0))
                .containsEntry("Name", "first")
                .containsEntry("Name_2", "second");
    }

    // ------------------------------------------------------------------ formats and edge cases

    @Test
    @DisplayName("reads the legacy .xls format")
    void readsXls() {
        SheetTable table = read(Workbooks.employeesXls(), "Employees", 1);

        assertThat(table.headers()).containsExactly("EmployeeId", "Name", "Department", "Salary");
        assertThat(table.rowCount()).isEqualTo(1);
        assertThat(table.cell(table.rows().get(0), "Salary").toJson()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("an empty sheet reads as an empty table rather than failing")
    void emptySheet() {
        SheetTable table = read(Workbooks.emptySheet(), "Empty", -1);

        assertThat(table.rowCount()).isZero();
        assertThat(table.columnCount()).isZero();
    }

    @Test
    @DisplayName("lists every sheet with its dimensions")
    void describesSheets() {
        try (WorkbookReader reader =
                     WorkbookReader.open(new ByteArrayInputStream(Workbooks.multiSheet()), LIMITS)) {
            List<Map<String, Object>> sheets = reader.describeSheets();

            assertThat(sheets).hasSize(3);
            assertThat(sheets.get(0)).containsEntry("name", "Employees").containsEntry("rows", 2);
            assertThat(reader.sheetNames()).containsExactly("Employees", "Departments", "Archive");
        }
    }

    @Test
    @DisplayName("a missing sheet names the ones that exist")
    void missingSheetIsHelpful() {
        assertThatThrownBy(() -> read(Workbooks.multiSheet(), "Payroll", 1))
                .isInstanceOf(ExcelException.class)
                .satisfies(ex -> assertThat(((ExcelException) ex).errorCode())
                        .isEqualTo("EXCEL_SHEET_NOT_FOUND"))
                // Naming the alternatives is what turns a dead end into a fix.
                .hasMessageContaining("Employees")
                .hasMessageContaining("Departments");
    }

    // ------------------------------------------------------------------ corrupt input

    @Test
    @DisplayName("a file that is not a workbook is rejected with a clear code")
    void rejectsNonWorkbook() {
        byte[] notAWorkbook = "this is plain text, not a spreadsheet".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> WorkbookReader.open(new ByteArrayInputStream(notAWorkbook), LIMITS))
                .isInstanceOf(ExcelException.class)
                .satisfies(ex -> assertThat(((ExcelException) ex).errorCode())
                        .isIn("EXCEL_INVALID_FORMAT", "EXCEL_CORRUPTED"));
    }

    @Test
    @DisplayName("an empty file is rejected")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> WorkbookReader.open(new ByteArrayInputStream(new byte[0]), LIMITS))
                .isInstanceOf(ExcelException.class)
                .satisfies(ex -> assertThat(((ExcelException) ex).errorCode())
                        .isEqualTo("EXCEL_INVALID_FORMAT"));
    }

    @Test
    @DisplayName("a truncated workbook is reported as corrupt rather than throwing something raw")
    void rejectsTruncatedWorkbook() {
        byte[] full = Workbooks.employees();
        byte[] truncated = java.util.Arrays.copyOf(full, full.length / 2);

        assertThatThrownBy(() -> WorkbookReader.open(new ByteArrayInputStream(truncated), LIMITS))
                .isInstanceOf(ExcelException.class);
    }

    // ------------------------------------------------------------------ limits

    @Test
    @DisplayName("a sheet with more rows than the limit is refused, naming both numbers")
    void enforcesRowLimit() {
        ExcelLimits tight = new ExcelLimits(10, 512, 32L * 1024 * 1024, Duration.ofMinutes(1));

        assertThatThrownBy(() -> {
            try (WorkbookReader reader =
                         WorkbookReader.open(new ByteArrayInputStream(Workbooks.rows(50)), tight)) {
                reader.read("Big", 1, 0, 0, false);
            }
        })
                .isInstanceOf(ExcelException.class)
                .satisfies(ex -> assertThat(((ExcelException) ex).errorCode())
                        .isEqualTo("EXCEL_FILE_TOO_LARGE"))
                .hasMessageContaining("10 row limit");
    }

    @Test
    @DisplayName("configured limits are clamped to the absolute ceiling")
    void clampsConfiguredLimits() {
        // A workflow asking for a billion rows is asking to take the engine down with it.
        ExcelLimits limits = ExcelLimits.from(new MapConfiguration(Map.of(
                "maxRows", 999_999_999, "maxColumns", 999_999, "maxProcessingSeconds", 86_400)));

        assertThat(limits.maxRows()).isEqualTo(1_000_000);
        assertThat(limits.maxColumns()).isEqualTo(16_384);
        assertThat(limits.maxProcessingTime()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("a row range reads only what was asked for")
    void readsARowRange() {
        try (WorkbookReader reader =
                     WorkbookReader.open(new ByteArrayInputStream(Workbooks.employees()), LIMITS)) {
            SheetTable table = reader.read("Employees", 1, 2, 3, false);

            assertThat(table.rowCount()).isEqualTo(2);
            assertThat(table.toMaps().get(0)).containsEntry("Name", "John");
        }
    }
}
