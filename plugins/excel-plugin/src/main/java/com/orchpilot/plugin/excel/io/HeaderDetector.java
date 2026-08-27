package com.orchpilot.plugin.excel.io;

import com.orchpilot.plugin.excel.model.CellType;
import com.orchpilot.plugin.excel.model.CellValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Works out which row holds the column names.
 *
 * <h2>Why guessing is worth doing</h2>
 *
 * Real spreadsheets rarely start at A1. They carry a title, a blank line, an export timestamp, and only then
 * the header. Requiring an author to count rows makes a workflow break the moment somebody adds a line to the
 * report it consumes — so the default is to detect, with an explicit override always available.
 *
 * <h2>How it scores</h2>
 *
 * A header row looks unlike the data beneath it, and that difference is what gets measured over the first few
 * rows:
 *
 * <ul>
 *   <li><b>Textual</b> — headers are words; a row of numbers is data.</li>
 *   <li><b>Unique</b> — column names do not repeat; a data row easily has the same value twice.</li>
 *   <li><b>Full</b> — headers are populated across the row; data rows have gaps.</li>
 *   <li><b>Unlike the row below</b> — the strongest signal. A header sits above rows of a different shape,
 *       so a row whose types differ from the next row's scores well, and one identical to it scores nothing.
 *       This is what stops the second row of a headerless export from being mistaken for a header.</li>
 * </ul>
 *
 * <p>Detection is a heuristic and is documented as one. When nothing scores above the floor the caller is told
 * no header was found, rather than being handed a wrong guess: synthetic {@code Column1…ColumnN} names are a
 * better outcome than silently eating the first row of data.
 */
public final class HeaderDetector {

    /** Beyond this, a spreadsheet that starts with a long preamble is not one detection should chase. */
    private static final int MAX_ROWS_SCANNED = 20;

    /** Below this score nothing is treated as a header. Tuned so an all-numeric row never wins. */
    private static final double MINIMUM_SCORE = 0.45;

    private HeaderDetector() {
    }

    /**
     * @param rows the first rows of the sheet, in order
     * @return the zero-based index of the likely header row, or -1 when none is convincing
     */
    public static int detect(List<List<CellValue>> rows) {
        if (rows == null || rows.isEmpty()) {
            return -1;
        }
        int scanned = Math.min(rows.size(), MAX_ROWS_SCANNED);
        int best = -1;
        double bestScore = MINIMUM_SCORE;

        for (int i = 0; i < scanned; i++) {
            List<CellValue> row = rows.get(i);
            if (isEffectivelyEmpty(row)) {
                continue;
            }
            List<CellValue> next = i + 1 < rows.size() ? rows.get(i + 1) : null;
            // A header with nothing under it is a single-row sheet, which has no data to name.
            if (next == null || isEffectivelyEmpty(next)) {
                continue;
            }
            double score = score(row, next);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** @return a score in [0,1]; higher means more header-like */
    private static double score(List<CellValue> candidate, List<CellValue> next) {
        int populated = 0;
        int textual = 0;
        Set<String> distinct = new HashSet<>();

        for (CellValue cell : candidate) {
            if (cell == null || cell.isBlank()) {
                continue;
            }
            populated++;
            if (cell.type() == CellType.STRING) {
                textual++;
            }
            distinct.add(cell.asText().trim().toLowerCase(Locale.ROOT));
        }
        if (populated == 0) {
            return 0;
        }

        double textShare = (double) textual / populated;
        double uniqueShare = (double) distinct.size() / populated;
        // Density is measured against the wider of the two rows, so a header narrower than its data is
        // penalised rather than rewarded for being short.
        double density = (double) populated / Math.max(1, Math.max(candidate.size(), next.size()));
        double difference = typeDifference(candidate, next);

        // Weighted towards the two signals that actually discriminate. Text share alone would pick any row of
        // words; difference alone would pick a one-off oddity. Together they identify a header.
        return 0.30 * textShare + 0.20 * uniqueShare + 0.15 * density + 0.35 * difference;
    }

    /** @return the share of columns where the candidate's type differs from the row below it */
    private static double typeDifference(List<CellValue> candidate, List<CellValue> next) {
        int compared = 0;
        int differing = 0;
        int width = Math.min(candidate.size(), next.size());
        for (int i = 0; i < width; i++) {
            CellValue above = candidate.get(i);
            CellValue below = next.get(i);
            if (above == null || below == null || above.isBlank() || below.isBlank()) {
                continue;
            }
            compared++;
            if (above.type() != below.type()) {
                differing++;
            }
        }
        return compared == 0 ? 0 : (double) differing / compared;
    }

    private static boolean isEffectivelyEmpty(List<CellValue> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (CellValue cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Turns a header row into usable column names.
     *
     * <p>Blank and duplicate names are resolved rather than rejected, because both are ordinary in exported
     * spreadsheets and neither is worth failing a workflow over. A blank becomes {@code ColumnN} by position;
     * a duplicate gains a numeric suffix, so {@code Name, Name} becomes {@code Name, Name_2} and a caller can
     * still address both.
     *
     * @param headerRow the detected header row, or null for a sheet with no header
     * @param width     the number of columns the data actually has
     */
    public static List<String> toColumnNames(List<CellValue> headerRow, int width) {
        List<String> names = new ArrayList<>(width);
        Set<String> used = new HashSet<>();

        for (int i = 0; i < width; i++) {
            CellValue cell = headerRow != null && i < headerRow.size() ? headerRow.get(i) : null;
            String name = cell == null || cell.isBlank() ? "" : cell.asText().trim();
            if (name.isEmpty()) {
                name = "Column" + (i + 1);
            }
            String unique = name;
            int suffix = 2;
            while (!used.add(unique.toLowerCase(Locale.ROOT))) {
                unique = name + "_" + suffix++;
            }
            names.add(unique);
        }
        return names;
    }
}
