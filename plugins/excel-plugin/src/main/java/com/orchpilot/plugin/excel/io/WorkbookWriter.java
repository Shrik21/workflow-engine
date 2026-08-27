package com.orchpilot.plugin.excel.io;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellType;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.SheetTable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Writes {@link SheetTable}s out as an .xlsx workbook.
 *
 * <h2>Streaming, because generation is where the memory goes</h2>
 *
 * {@link SXSSFWorkbook} keeps only a sliding window of rows in memory and flushes the rest to temporary files,
 * so producing a 200 000-row report costs a bounded amount of heap instead of one Java object per cell. That is
 * the difference between a large export succeeding and an engine-wide {@code OutOfMemoryError}, and it is why
 * generation always uses it — there is no threshold to get wrong.
 *
 * <p>SXSSF's temporary files must be disposed explicitly; {@link #close()} does that, and every caller uses
 * try-with-resources. Leaking them would fill the engine's temp directory over time.
 *
 * <h2>Formulas are written as text, never evaluated</h2>
 *
 * A formula string is placed in the cell for Excel to compute when the file is opened. This plugin does not
 * evaluate it, and {@link #ALLOWED_FUNCTIONS} restricts what may be written at all — so a workflow cannot use
 * a generated workbook to smuggle in something like a {@code WEBSERVICE} call that would fire on open.
 */
public final class WorkbookWriter implements AutoCloseable {

    /**
     * The functions a workflow may write into a cell.
     *
     * <p>An allow-list because Excel's function set includes several that reach outside the document —
     * {@code WEBSERVICE} and {@code RTD} fetch remote content, and {@code HYPERLINK} plus {@code CALL} have
     * their own history. A workbook this plugin generates is opened by a person who trusts it, so what goes
     * into it is restricted to arithmetic, lookup and formatting.
     */
    public static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "SUM", "AVERAGE", "COUNT", "COUNTA", "MIN", "MAX", "IF", "AND", "OR",
            "VLOOKUP", "XLOOKUP", "HLOOKUP", "INDEX", "MATCH",
            "CONCAT", "CONCATENATE", "TEXT", "DATE", "TODAY", "NOW", "ROUND", "ROUNDUP", "ROUNDDOWN",
            "ABS", "LEN", "LEFT", "RIGHT", "MID", "TRIM", "UPPER", "LOWER", "PROPER",
            "SUMIF", "COUNTIF", "AVERAGEIF", "IFERROR");

    /** Rows held in memory before SXSSF flushes to disk. 100 is POI's own recommended default. */
    private static final int WINDOW_SIZE = 100;

    private final SXSSFWorkbook workbook;
    private CellStyle headerStyle;
    private CellStyle dateStyle;
    private CellStyle dateTimeStyle;

    public WorkbookWriter() {
        this.workbook = new SXSSFWorkbook(WINDOW_SIZE);
        // Compressing the flushed temporary files trades a little CPU for much less temp-directory pressure
        // when several large exports run at once.
        this.workbook.setCompressTempFiles(true);
    }

    /**
     * Appends a sheet.
     *
     * @param table the data; its sheet name is sanitised to what Excel permits
     */
    public void addSheet(SheetTable table) {
        Sheet sheet = workbook.createSheet(safeSheetName(table.sheetName()));
        List<String> headers = table.headers();

        int rowIndex = 0;
        if (!headers.isEmpty()) {
            Row header = sheet.createRow(rowIndex++);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(headerStyle());
            }
        }

        for (List<CellValue> values : table.rows()) {
            Row row = sheet.createRow(rowIndex++);
            for (int c = 0; c < headers.size(); c++) {
                write(row.createCell(c), table.cell(values, c));
            }
        }
    }

    /** Writes one value into a cell, preserving its type so Excel treats it as data rather than text. */
    private void write(Cell cell, CellValue value) {
        if (value == null || value.isBlank()) {
            cell.setBlank();
            return;
        }
        switch (value.type()) {
            case FORMULA -> writeFormula(cell, value);
            case INTEGER, LONG -> cell.setCellValue(((Number) value.value()).doubleValue());
            case DECIMAL -> cell.setCellValue(value.value() instanceof BigDecimal decimal
                    ? decimal.doubleValue() : Double.parseDouble(value.asText()));
            case BOOLEAN -> cell.setCellValue(Boolean.TRUE.equals(value.value()));
            case DATE -> {
                cell.setCellValue(value.value() instanceof LocalDate date ? date : LocalDate.now());
                cell.setCellStyle(dateStyle());
            }
            case DATETIME -> {
                cell.setCellValue(value.value() instanceof LocalDateTime dateTime
                        ? dateTime : LocalDateTime.now());
                cell.setCellStyle(dateTimeStyle());
            }
            // An error value is written as its text so the workbook stays readable rather than carrying a
            // real Excel error the recipient has to interpret.
            case ERROR, STRING, BLANK -> cell.setCellValue(value.asText());
        }
    }

    /**
     * Writes a formula, after checking every function it names is on the allow-list.
     *
     * @throws ExcelException when the formula uses a function that is not permitted
     */
    private void writeFormula(Cell cell, CellValue value) {
        String formula = value.formula();
        if (formula == null || formula.isBlank()) {
            cell.setBlank();
            return;
        }
        String rejected = firstDisallowedFunction(formula);
        if (rejected != null) {
            throw ExcelException.invalidData("The formula uses '" + rejected + "', which this plugin does not "
                    + "allow to be written. Permitted functions: "
                    + String.join(", ", new java.util.TreeSet<>(ALLOWED_FUNCTIONS)) + ".");
        }
        try {
            cell.setCellFormula(formula);
        } catch (RuntimeException ex) {
            throw ExcelException.invalidData("The formula could not be written: " + formula);
        }
    }

    /**
     * @return the first function name in the formula that is not allowed, or null when all are
     */
    static String firstDisallowedFunction(String formula) {
        // A function call is a name immediately followed by "(" — which also correctly ignores a cell
        // reference or a defined name, since neither is followed by a parenthesis.
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([A-Za-z][A-Za-z0-9._]*)\\s*\\(").matcher(formula);
        while (matcher.find()) {
            String name = matcher.group(1).toUpperCase(Locale.ROOT);
            // Excel 365 prefixes newer functions with "_xlfn."; compare on the bare name.
            String bare = name.startsWith("_XLFN.") ? name.substring("_XLFN.".length()) : name;
            if (!ALLOWED_FUNCTIONS.contains(bare)) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * Serialises the workbook.
     *
     * <p>Returns bytes rather than writing to a stream the caller supplies, because the result goes straight
     * to the file service and its size is already bounded by the row limits that produced it.
     */
    public byte[] toBytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw ExcelException.storageError("The workbook could not be serialised.", ex);
        }
    }

    /**
     * Excel refuses sheet names over 31 characters or containing {@code : \ / ? * [ ]}.
     *
     * <p>Cleaned rather than rejected: the name usually comes from a data value during a split, and failing a
     * split because a department is called "R&D / Eng" would be unhelpful.
     */
    static String safeSheetName(String name) {
        if (name == null || name.isBlank()) {
            return "Sheet1";
        }
        String cleaned = WorkbookUtil.createSafeSheetName(name.trim());
        return cleaned.isBlank() ? "Sheet1" : cleaned;
    }

    private CellStyle headerStyle() {
        if (headerStyle == null) {
            headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
        }
        return headerStyle;
    }

    private CellStyle dateStyle() {
        if (dateStyle == null) {
            dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        }
        return dateStyle;
    }

    private CellStyle dateTimeStyle() {
        if (dateTimeStyle == null) {
            dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
        }
        return dateTimeStyle;
    }

    /** @return the bytes of a single-sheet workbook, the common case */
    public static byte[] singleSheet(SheetTable table) {
        try (WorkbookWriter writer = new WorkbookWriter()) {
            writer.addSheet(table);
            return writer.toBytes();
        }
    }

    /**
     * Disposes SXSSF's temporary files.
     *
     * <p>Not optional: without it every generated workbook leaves its spill files behind, and an engine
     * producing reports on a schedule fills its temp directory.
     */
    @Override
    public void close() {
        try {
            workbook.dispose();
            workbook.close();
        } catch (IOException | RuntimeException ex) {
            // Nothing useful to do; the bytes are already produced by this point.
        }
    }

    /** Converts a POI workbook's sheets to tables, used by the sheet-restructuring operations. */
    public static Workbook rawWorkbook(WorkbookWriter writer) {
        return writer.workbook;
    }

    /** @return whether a cell type needs a data format applied when written */
    static boolean needsStyle(CellType type) {
        return type == CellType.DATE || type == CellType.DATETIME;
    }
}
