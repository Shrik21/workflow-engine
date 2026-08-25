package com.orchpilot.plugin.excel.support;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds real workbooks for tests.
 *
 * <p>Real POI output rather than fixture files checked into the repository: the bytes are produced by the same
 * library that will read them, so a test cannot pass against a stale fixture that no longer resembles what
 * Excel actually writes. It also means the .xls and .xlsx cases are the same code with one flag.
 */
public final class Workbooks {

    private Workbooks() {
    }

    /** The employee sheet used across the tests: headers plus three typed rows. */
    public static byte[] employees() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");
            header(sheet, "EmployeeId", "Name", "Department", "Salary", "HireDate", "Active");

            row(workbook, sheet, 1, "001", "John", "IT", 50000, LocalDate.of(2021, 3, 1), true);
            row(workbook, sheet, 2, "002", "Sarah", "HR", 60000, LocalDate.of(2020, 7, 15), true);
            row(workbook, sheet, 3, "003", "Raj", "IT", 75000, LocalDate.of(2022, 1, 10), false);
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The same data in the legacy .xls format. */
    public static byte[] employeesXls() {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Employees");
            header(sheet, "EmployeeId", "Name", "Department", "Salary");

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("001");
            first.createCell(1).setCellValue("John");
            first.createCell(2).setCellValue("IT");
            first.createCell(3).setCellValue(50000);
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A workbook whose header is preceded by a title and a blank row, for header detection. */
    public static byte[] withPreamble() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Report");
            sheet.createRow(0).createCell(0).setCellValue("Quarterly Headcount Report");
            sheet.createRow(1);
            header(sheet, 2, "Name", "Department", "Salary");

            Row data = sheet.createRow(3);
            data.createCell(0).setCellValue("John");
            data.createCell(1).setCellValue("IT");
            data.createCell(2).setCellValue(50000);

            Row second = sheet.createRow(4);
            second.createCell(0).setCellValue("Sarah");
            second.createCell(1).setCellValue("HR");
            second.createCell(2).setCellValue(60000);
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A sheet containing a formula with a cached result, and one blank cell. */
    public static byte[] withFormula() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Totals");
            header(sheet, "Item", "Amount", "Total");

            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("A");
            first.createCell(1).setCellValue(100);
            Cell formula = first.createCell(2);
            formula.setCellFormula("B2*2");

            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("B");
            // Amount deliberately left blank.
            second.createCell(2).setCellValue(0);

            // Compute cached values, so the reader's preferred path (cached result) is exercised.
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A workbook with several sheets, for the workbook-level and sheet operations. */
    public static byte[] multiSheet() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet employees = workbook.createSheet("Employees");
            header(employees, "Name", "Department");
            Row row = employees.createRow(1);
            row.createCell(0).setCellValue("John");
            row.createCell(1).setCellValue("IT");

            Sheet departments = workbook.createSheet("Departments");
            header(departments, "Code", "Name");
            Row department = departments.createRow(1);
            department.createCell(0).setCellValue("IT");
            department.createCell(1).setCellValue("Technology");

            workbook.createSheet("Archive");
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A sheet whose header row repeats a name, for duplicate-header handling. */
    public static byte[] duplicateHeaders() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            header(sheet, "Name", "Name", "Value");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("first");
            row.createCell(1).setCellValue("second");
            row.createCell(2).setCellValue(1);
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** An entirely empty workbook with one empty sheet. */
    public static byte[] emptySheet() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Empty");
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A sheet with a given number of data rows, for the limit tests. */
    public static byte[] rows(int count) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Big");
            header(sheet, "Id", "Value");
            for (int i = 1; i <= count; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue("row-" + i);
            }
            return bytes(workbook);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void header(Sheet sheet, String... names) {
        header(sheet, 0, names);
    }

    private static void header(Sheet sheet, int rowIndex, String... names) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < names.length; i++) {
            row.createCell(i).setCellValue(names[i]);
        }
    }

    private static void row(Workbook workbook, Sheet sheet, int index, String id, String name,
                            String department, double salary, LocalDate hireDate, boolean active) {
        Row row = sheet.createRow(index);
        row.createCell(0).setCellValue(id);
        row.createCell(1).setCellValue(name);
        row.createCell(2).setCellValue(department);
        row.createCell(3).setCellValue(salary);

        Cell date = row.createCell(4);
        date.setCellValue(hireDate);
        // Without a date format POI stores a plain number and the reader would correctly report it as one.
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        date.setCellStyle(style);

        row.createCell(5).setCellValue(active);
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
