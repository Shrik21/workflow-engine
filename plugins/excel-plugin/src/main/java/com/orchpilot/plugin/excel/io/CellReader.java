package com.orchpilot.plugin.excel.io;

import com.orchpilot.plugin.excel.model.CellType;
import com.orchpilot.plugin.excel.model.CellValue;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.RichTextString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Turns one POI cell into a {@link CellValue}, deciding what type it really is.
 *
 * <h2>Excel has fewer types than it appears to</h2>
 *
 * Every number in a spreadsheet is a double, and a date is a double with a date-like display format. Reporting
 * that faithfully would give a workflow a column of {@code 45231.0} where it expected {@code 2023-11-15}, and
 * {@code 50000.0} where it expected an employee's salary as a whole number. So this narrows: a numeric cell is
 * examined for a date format first, then for whether it is integral, and reported accordingly.
 *
 * <h2>Formulas are never executed as code</h2>
 *
 * Two modes, both safe. Asking for formula <em>text</em> returns the string and evaluates nothing. Asking for
 * <em>calculated values</em> uses POI's cached result where the workbook has one and otherwise POI's own
 * evaluator, which implements a fixed set of spreadsheet functions in Java — it is not an interpreter for
 * arbitrary code, and it cannot reach the filesystem, the network or the JVM. A formula that fails to evaluate
 * becomes an {@link CellType#ERROR} value rather than an exception, because one broken cell must not fail a
 * node reading ten thousand good ones.
 */
public final class CellReader {

    private final boolean readFormulas;
    private final FormulaEvaluator evaluator;

    /**
     * @param readFormulas true to report a formula's text, false to report its calculated value
     * @param evaluator    POI evaluator used only when {@code readFormulas} is false; may be null
     */
    public CellReader(boolean readFormulas, FormulaEvaluator evaluator) {
        this.readFormulas = readFormulas;
        this.evaluator = evaluator;
    }

    /** @return the cell's value, never null; an absent cell reads as blank */
    public CellValue read(Cell cell) {
        if (cell == null) {
            return CellValue.BLANK;
        }
        return switch (cell.getCellType()) {
            case STRING -> fromString(cell.getRichStringCellValue());
            case NUMERIC -> fromNumeric(cell);
            case BOOLEAN -> CellValue.of(cell.getBooleanCellValue());
            case FORMULA -> fromFormula(cell);
            case ERROR -> CellValue.error(errorText(cell.getErrorCellValue()));
            case BLANK, _NONE -> CellValue.BLANK;
        };
    }

    private CellValue fromString(RichTextString rich) {
        if (rich == null) {
            return CellValue.BLANK;
        }
        String text = rich.getString();
        return text == null || text.isEmpty() ? CellValue.BLANK : CellValue.of(text);
    }

    /**
     * Narrows a numeric cell.
     *
     * <p>The date check comes first and must: {@code 45231} is a perfectly ordinary integer and also
     * 2023-11-15, and only the cell's format tells them apart.
     */
    private CellValue fromNumeric(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
            if (dateTime == null) {
                return CellValue.BLANK;
            }
            // Midnight almost always means the author meant a date, not an instant.
            LocalDate date = dateTime.toLocalDate();
            return dateTime.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                    ? CellValue.of(date)
                    : CellValue.of(dateTime);
        }
        return fromDouble(cell.getNumericCellValue());
    }

    private CellValue fromDouble(double number) {
        // Integral and within long range: report it as a whole number so it renders as 50000, not 50000.0.
        if (number == Math.rint(number) && !Double.isInfinite(number)
                && Math.abs(number) < 9.007199254740992E15) {
            return CellValue.of((long) number);
        }
        // BigDecimal.valueOf uses the double's shortest representation, avoiding 0.1 becoming
        // 0.1000000000000000055511151231257827.
        return CellValue.of(BigDecimal.valueOf(number));
    }

    private CellValue fromFormula(Cell cell) {
        String formulaText = safeFormula(cell);
        if (readFormulas) {
            return CellValue.formula(formulaText);
        }
        try {
            CellValue result = evaluate(cell);
            return CellValue.calculated(result, formulaText);
        } catch (RuntimeException ex) {
            // POI throws for unimplemented functions and for circular references. One such cell must not cost
            // the caller the whole sheet, so it is reported as an error value in place.
            return CellValue.calculated(CellValue.error("#UNEVALUATED"), formulaText);
        }
    }

    /**
     * Reads a formula's result, preferring the value Excel already cached.
     *
     * <p>The cached value is what Excel itself last computed, so it is both free and more likely to be right
     * than a re-evaluation with a function POI implements slightly differently. Evaluation is the fallback for
     * a workbook written by a tool that cached nothing.
     */
    private CellValue evaluate(Cell cell) {
        org.apache.poi.ss.usermodel.CellType cached = cell.getCachedFormulaResultType();
        if (cached != org.apache.poi.ss.usermodel.CellType._NONE) {
            return switch (cached) {
                case STRING -> fromString(cell.getRichStringCellValue());
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? fromNumeric(cell)
                        : fromDouble(cell.getNumericCellValue());
                case BOOLEAN -> CellValue.of(cell.getBooleanCellValue());
                case ERROR -> CellValue.error(errorText(cell.getErrorCellValue()));
                default -> evaluateWithPoi(cell);
            };
        }
        return evaluateWithPoi(cell);
    }

    private CellValue evaluateWithPoi(Cell cell) {
        if (evaluator == null) {
            return CellValue.error("#UNEVALUATED");
        }
        org.apache.poi.ss.usermodel.CellValue evaluated = evaluator.evaluate(cell);
        if (evaluated == null) {
            return CellValue.BLANK;
        }
        return switch (evaluated.getCellType()) {
            case STRING -> CellValue.of(evaluated.getStringValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? fromNumeric(cell)
                    : fromDouble(evaluated.getNumberValue());
            case BOOLEAN -> CellValue.of(evaluated.getBooleanValue());
            case ERROR -> CellValue.error(errorText(evaluated.getErrorValue()));
            default -> CellValue.BLANK;
        };
    }

    /** POI throws when a cell's formula cannot be rendered; the text is diagnostic, not essential. */
    private static String safeFormula(Cell cell) {
        try {
            return cell.getCellFormula();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String errorText(byte errorCode) {
        try {
            return org.apache.poi.ss.usermodel.FormulaError.forInt(errorCode).getString();
        } catch (RuntimeException ex) {
            return "#ERROR";
        }
    }
}
