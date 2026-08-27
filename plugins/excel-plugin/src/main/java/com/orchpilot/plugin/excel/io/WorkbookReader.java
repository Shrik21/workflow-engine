package com.orchpilot.plugin.excel.io;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.ExcelLimits;
import com.orchpilot.plugin.excel.model.SheetTable;
import org.apache.poi.EmptyFileException;
import org.apache.poi.poifs.filesystem.NotOLE2FileException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opens a workbook and reads sheets into {@link SheetTable}s.
 *
 * <h2>The only place POI is opened</h2>
 *
 * Every operation in this plugin either reads through here or writes through {@link WorkbookWriter}. Keeping
 * the library at two edges means the limit checks, the zip-bomb guard and the error mapping exist once, and
 * everything downstream is ordinary Java operating on ordinary lists.
 *
 * <h2>Macros are never executed</h2>
 *
 * A {@code .xlsm} opens as data. POI does not run VBA and this plugin never asks it to — there is no code path
 * here that could, because the macro storage is simply not read. That is a property of what is not written
 * rather than a check that could be bypassed.
 *
 * <h2>Bounded before it is trusted</h2>
 *
 * The input is read under this node's own byte ceiling before POI sees it, so an oversized file fails on a
 * counter rather than on an allocation. Rows and columns are then counted as they are read, and the deadline is
 * checked every row, so a pathological sheet stops at a row boundary where stopping is safe.
 *
 * <p>Expansion — the zip-bomb case — is left to POI's own per-record maxima and inflate-ratio guard, which are
 * on by default. The tempting alternative, {@code IOUtils.setByteArrayMaxOverride}, is a JVM-wide static: a
 * plugin setting it would silently change allocation limits for the engine and every other plugin sharing the
 * process, and a value low enough to be a useful guard here is low enough to break POI's own buffers.
 */
public final class WorkbookReader implements AutoCloseable {

    private final Workbook workbook;
    private final ExcelLimits limits;

    private WorkbookReader(Workbook workbook, ExcelLimits limits) {
        this.workbook = workbook;
        this.limits = limits;
    }

    /**
     * Opens a workbook from a stream.
     *
     * @param content the workbook bytes; fully consumed and closed by this method
     * @param limits  the bounds this attempt must respect
     * @throws ExcelException with a normalised code when the content is not a usable workbook
     */
    public static WorkbookReader open(InputStream content, ExcelLimits limits) {
        // Read under our own ceiling first, rather than reaching for POI's IOUtils.setByteArrayMaxOverride.
        // That setter is a JVM-wide static: a plugin calling it would change allocation limits for the engine
        // and for every other plugin in the process, and setting it low enough to be a useful guard here also
        // breaks POI's own internal buffers. Bounding the input is the part that is actually ours to enforce;
        // POI's built-in per-record maxima and inflate-ratio guard handle expansion, and they stay at their
        // defaults where they belong.
        byte[] bytes = readBounded(content, limits);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return new WorkbookReader(WorkbookFactory.create(in), limits);
        } catch (EmptyFileException ex) {
            throw ExcelException.invalidFormat("The file is empty.");
        } catch (NotOLE2FileException ex) {
            throw ExcelException.invalidFormat("It is not an .xlsx, .xls or .xlsm workbook.");
        } catch (IOException ex) {
            throw ExcelException.corrupted(ex);
        } catch (RuntimeException ex) {
            // POI signals a truncated or tampered archive through several unrelated runtime types, so this
            // catch is deliberately broad rather than an enumeration that would miss the next one.
            throw ExcelException.corrupted(ex);
        }
    }

    /**
     * Reads the whole stream, refusing to exceed the configured size.
     *
     * <p>Counts as it copies rather than trusting a declared length, because the declared length of an upload
     * is a client-supplied number and a stream can always deliver more than it claimed. The buffer grows to at
     * most one chunk beyond the limit before the check fires.
     *
     * @throws ExcelException when the content is larger than this node permits
     */
    private static byte[] readBounded(InputStream content, ExcelLimits limits) {
        try (InputStream in = content;
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limits.maxFileBytes()) {
                    throw ExcelException.tooLarge("The file is larger than the "
                            + limits.maxFileBytes() + " byte limit.");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw ExcelException.storageError("The workbook could not be read.", ex);
        }
    }

    /** @return every sheet name, in workbook order */
    public List<String> sheetNames() {
        List<String> names = new ArrayList<>(workbook.getNumberOfSheets());
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    /**
     * Summarises every sheet without reading its cells.
     *
     * @return one entry per sheet with its name, row count and column count
     */
    public List<Map<String, Object>> describeSheets() {
        List<Map<String, Object>> described = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", sheet.getSheetName());
            // getLastRowNum is the zero-based index of the last row, so a one-row sheet returns 0.
            entry.put("rows", sheet.getPhysicalNumberOfRows());
            entry.put("columns", widestRow(sheet));
            entry.put("index", i);
            entry.put("hidden", workbook.isSheetHidden(i));
            described.add(entry);
        }
        return described;
    }

    /**
     * Resolves a sheet by name, or the first sheet when no name is given.
     *
     * @throws ExcelException naming the available sheets when the requested one does not exist
     */
    public Sheet requireSheet(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            if (workbook.getNumberOfSheets() == 0) {
                throw ExcelException.invalidFormat("The workbook contains no sheets.");
            }
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw ExcelException.sheetNotFound(sheetName, sheetNames());
        }
        return sheet;
    }

    /**
     * Reads a sheet into a table.
     *
     * @param sheetName    the sheet, or null for the first
     * @param headerRow    1-based header row, 0 for none, or -1 to detect
     * @param startRow     1-based first data row, or 0 to start immediately after the header
     * @param endRow       1-based last data row, or 0 for all
     * @param readFormulas report formula text rather than calculated values
     */
    public SheetTable read(String sheetName, int headerRow, int startRow, int endRow, boolean readFormulas) {
        Sheet sheet = requireSheet(sheetName);
        ExcelLimits.Deadline deadline = limits.startDeadline();
        CellReader cellReader = new CellReader(readFormulas, formulaEvaluator(readFormulas));

        List<List<CellValue>> raw = readRows(sheet, cellReader, deadline);
        if (raw.isEmpty()) {
            return SheetTable.empty(sheet.getSheetName());
        }

        int headerIndex = resolveHeaderIndex(headerRow, raw);
        int width = widestOf(raw);
        limits.checkColumnCount(width);

        List<String> headers = HeaderDetector.toColumnNames(
                headerIndex >= 0 && headerIndex < raw.size() ? raw.get(headerIndex) : null, width);

        int firstData = startRow > 0 ? startRow - 1 : headerIndex + 1;
        int lastData = endRow > 0 ? Math.min(endRow - 1, raw.size() - 1) : raw.size() - 1;

        List<List<CellValue>> data = new ArrayList<>();
        for (int i = Math.max(firstData, 0); i <= lastData; i++) {
            if (i == headerIndex) {
                continue;
            }
            List<CellValue> row = raw.get(i);
            // A trailing run of empty rows is normal in an exported sheet and is not data.
            if (isBlankRow(row)) {
                continue;
            }
            data.add(row);
        }
        return new SheetTable(sheet.getSheetName(), headers, data);
    }

    /** @return the header index to use: detected, explicit-minus-one, or -1 for "no header" */
    private int resolveHeaderIndex(int headerRow, List<List<CellValue>> raw) {
        if (headerRow < 0) {
            return HeaderDetector.detect(raw);
        }
        return headerRow == 0 ? -1 : headerRow - 1;
    }

    private List<List<CellValue>> readRows(Sheet sheet, CellReader cellReader,
                                           ExcelLimits.Deadline deadline) {
        List<List<CellValue>> raw = new ArrayList<>();
        int lastRow = sheet.getLastRowNum();
        limits.checkRowCount(lastRow + 1);

        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            // Checked per row rather than per cell: frequent enough to stop a runaway promptly, cheap enough
            // not to matter against the cost of reading a row.
            deadline.check();
            Row row = sheet.getRow(r);
            if (row == null) {
                raw.add(new ArrayList<>());
                continue;
            }
            int width = row.getLastCellNum();
            limits.checkColumnCount(Math.max(width, 0));

            List<CellValue> values = new ArrayList<>(Math.max(width, 0));
            for (int c = 0; c < width; c++) {
                values.add(cellReader.read(row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)));
            }
            raw.add(values);
        }
        return raw;
    }

    /**
     * @param readFormulas whether formula text was requested
     * @return an evaluator, or null when formulas are being read as text and none is needed
     */
    private org.apache.poi.ss.usermodel.FormulaEvaluator formulaEvaluator(boolean readFormulas) {
        if (readFormulas) {
            return null;
        }
        try {
            return workbook.getCreationHelper().createFormulaEvaluator();
        } catch (RuntimeException ex) {
            // Some workbooks refuse an evaluator; cached values still work, so this is not fatal.
            return null;
        }
    }

    private static boolean isBlankRow(List<CellValue> row) {
        for (CellValue cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static int widestOf(List<List<CellValue>> rows) {
        int width = 0;
        for (List<CellValue> row : rows) {
            width = Math.max(width, row.size());
        }
        return width;
    }

    private static int widestRow(Sheet sheet) {
        int width = 0;
        for (Row row : sheet) {
            width = Math.max(width, row.getLastCellNum());
        }
        return width;
    }

    /** @return the underlying workbook, for the sheet-level operations that restructure it */
    public Workbook workbook() {
        return workbook;
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException ex) {
            // Closing a read-only workbook cannot lose data; failing the node over it would be worse.
        }
    }
}
