package com.orchpilot.plugin.excel.io;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.CellValue;
import com.orchpilot.plugin.excel.model.ExcelLimits;
import com.orchpilot.plugin.excel.model.SheetTable;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV in both directions, written by hand rather than pulled in as a dependency.
 *
 * <h2>Why not a CSV library</h2>
 *
 * The plugin already carries POI and its transitive tree, and the loader enforces a JAR entry ceiling. RFC 4180
 * is small enough to implement correctly in a hundred lines — the whole difficulty is quoting, and that is
 * handled below in one place each way. Adding another library for it would cost more than it saves.
 *
 * <h2>The parts people get wrong</h2>
 *
 * <ul>
 *   <li>A quoted field may contain the delimiter, a newline, and doubled quotes. The parser is a state machine
 *       over characters rather than a {@code split}, because {@code split} cannot express any of that.</li>
 *   <li>A value that begins with {@code =}, {@code +}, {@code -} or {@code @} is a formula injection risk: many
 *       spreadsheet applications will execute it when the CSV is opened. Such values are prefixed with an
 *       apostrophe on export, which Excel treats as "this is text".</li>
 * </ul>
 */
public final class CsvCodec {

    /** Characters that make a spreadsheet treat a field as a formula when the CSV is opened. */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    private CsvCodec() {
    }

    /**
     * Renders a table as CSV.
     *
     * @param delimiter    field separator
     * @param quote        quote character
     * @param includeHeader write the header row
     */
    public static String write(SheetTable table, char delimiter, char quote, boolean includeHeader) {
        StringBuilder out = new StringBuilder();
        List<String> headers = table.headers();

        if (includeHeader && !headers.isEmpty()) {
            writeRow(out, headers, delimiter, quote);
        }
        for (List<CellValue> row : table.rows()) {
            List<String> values = new ArrayList<>(headers.size());
            for (int i = 0; i < headers.size(); i++) {
                values.add(table.cell(row, i).asText());
            }
            writeRow(out, values, delimiter, quote);
        }
        return out.toString();
    }

    private static void writeRow(StringBuilder out, List<String> values, char delimiter, char quote) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            out.append(escape(values.get(i), delimiter, quote));
        }
        out.append('\n');
    }

    /** Quotes a field when it needs it, and neutralises a leading formula character. */
    private static String escape(String value, char delimiter, char quote) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && FORMULA_STARTERS.indexOf(text.charAt(0)) >= 0) {
            // Prefixed rather than stripped: the content is preserved and simply not executed.
            text = "'" + text;
        }
        boolean needsQuotes = text.indexOf(delimiter) >= 0 || text.indexOf(quote) >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return text;
        }
        StringBuilder quoted = new StringBuilder(text.length() + 2);
        quoted.append(quote);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == quote) {
                quoted.append(quote);
            }
            quoted.append(character);
        }
        return quoted.append(quote).toString();
    }

    /**
     * Parses CSV into a table.
     *
     * @param content     the CSV text
     * @param delimiter   field separator
     * @param quote       quote character
     * @param hasHeader   treat the first row as column names
     * @param sheetName   the name for the resulting sheet
     * @param limits      the bounds this attempt must respect
     */
    public static SheetTable read(String content, char delimiter, char quote, boolean hasHeader,
                                  String sheetName, ExcelLimits limits) {
        if (content == null || content.isBlank()) {
            return SheetTable.empty(sheetName);
        }
        List<List<String>> rows = parse(content, delimiter, quote, limits);
        if (rows.isEmpty()) {
            return SheetTable.empty(sheetName);
        }

        int width = 0;
        for (List<String> row : rows) {
            width = Math.max(width, row.size());
        }
        limits.checkColumnCount(width);

        List<String> headers;
        int firstDataRow;
        if (hasHeader) {
            List<CellValue> headerCells = new ArrayList<>(rows.get(0).size());
            for (String value : rows.get(0)) {
                headerCells.add(CellValue.of(value));
            }
            headers = HeaderDetector.toColumnNames(headerCells, width);
            firstDataRow = 1;
        } else {
            headers = HeaderDetector.toColumnNames(null, width);
            firstDataRow = 0;
        }

        List<List<CellValue>> data = new ArrayList<>();
        for (int i = firstDataRow; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            List<CellValue> cells = new ArrayList<>(row.size());
            for (String value : row) {
                // Everything from CSV is text; a CONVERT transform step narrows it when the author wants that.
                cells.add(value.isEmpty() ? CellValue.BLANK : CellValue.of(value));
            }
            data.add(cells);
        }
        return new SheetTable(sheetName, headers, data);
    }

    /**
     * The state machine.
     *
     * <p>Tracks whether the cursor is inside quotes, which is the only way to know whether a delimiter or a
     * newline ends the field or belongs to it.
     */
    private static List<List<String>> parse(String content, char delimiter, char quote, ExcelLimits limits) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char character = content.charAt(i);

            if (inQuotes) {
                if (character == quote) {
                    // A doubled quote inside a quoted field is a literal quote.
                    if (i + 1 < content.length() && content.charAt(i + 1) == quote) {
                        field.append(quote);
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }

            if (character == quote) {
                inQuotes = true;
            } else if (character == delimiter) {
                current.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                // Consume the second half of a CRLF so it does not produce a phantom empty row.
                if (character == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                limits.checkRowCount(rows.size());
                current = new ArrayList<>();
            } else {
                field.append(character);
            }
        }

        // Whatever is left is the final field, unless the input ended with a newline.
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        if (inQuotes) {
            throw ExcelException.invalidData("The CSV ends inside a quoted field; a quote is unclosed.");
        }
        return rows;
    }

    /** @return the single character a configured delimiter names, defaulting to a comma */
    public static char delimiter(String configured) {
        if (configured == null || configured.isEmpty()) {
            return ',';
        }
        return switch (configured.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "TAB", "\\T" -> '\t';
            case "SEMICOLON" -> ';';
            case "PIPE" -> '|';
            case "COMMA" -> ',';
            default -> configured.charAt(0);
        };
    }
}
